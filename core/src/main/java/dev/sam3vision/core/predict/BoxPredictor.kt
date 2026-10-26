package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask

/**
 * Ce que le serveur dit d'un objet, indépendamment du transport : la boîte déjà
 * convertie et validée. Le prédicteur ne connaît ni JSON, ni OkHttp, ni Android —
 * c'est ce qui le rend testable en JVM pure.
 */
data class ServerObject(val id: Int, val label: String, val score: Float, val box: Box)

/**
 * Ce que l'UI doit dessiner **maintenant**, pour un id suivi.
 *
 * [useMask] dit quel rendu prendre : le masque PNG serveur (fidèle au contour) tant
 * qu'il est frais, sinon la boîte prédite (grossière mais à l'heure). C'est tout le
 * contrat du mode « masque serveur + suivi local ».
 */
data class PredictedObject(
    val id: Int,
    val label: String,
    val score: Float,
    val box: Box,
    /** Boîte issue de la dernière réponse (à quelques dizaines de ms près). */
    val anchored: Boolean,
    /** 1 = masque serveur frais, 0 = plus rien de crédible à afficher. */
    val confidence: Float,
    /** Âge de la dernière observation serveur, en ms. */
    val ageMs: Long,
) {
    val useMask: Boolean get() = anchored && confidence >= MotionModel.MASK_FLOOR
}

/** Bilan d'une absorption de réponse serveur — à journaliser pour régler les seuils. */
data class SyncReport(
    val created: Int,
    val anchored: Int,
    val jumped: Int,
    val dropped: Int,
    /**
     * IoU moyen entre la boîte **prédite** (juste avant la réponse) et la boîte
     * **serveur** reçue. C'est la métrique de dérive à surveiller sur l'appareil :
     * un IoU moyen qui s'effondre = le masque colle mal. 1 = prédiction parfaite.
     */
    val meanIou: Float = 0f,
) {
    val touched: Int get() = created + anchored
}

/**
 * Table des ids suivis entre deux réponses serveur.
 *
 * Cycle : [onServer] absorbe une réponse (`POST /frame`, ids stables) ; [predictAt] est
 * appelé à **chaque frame caméra** (30 fps) et extrapole chaque boîte depuis son ancre,
 * éventuellement corrigée par le flot optique entre deux frames (`prev`/`cur`).
 * Le résultat est la seule chose que l'UI dessine en continu : le réseau ne cadence
 * plus le rendu, il ne fait que le corriger.
 *
 * Gestion des ids : un id absent d'une réponse n'est pas supprimé tout de suite
 * ([maxCoastMs] de « vol libre » pour survivre à un tracker qui rate une frame) puis
 * disparaît définitivement après [dropAfterMs]. Le garde-fou de dérive vit dans
 * [MotionModel.observe] (IoU < seuil → ré-ancrage brutal).
 *
 * **Thread-safety** : une seule instance, mutations sous [lock] ; `predictAt` renvoie
 * une liste immuable, jamais une vue sur l'état interne.
 */
class BoxPredictor(
    private val maxCoastMs: Long = DEFAULT_COAST_MS,
    private val dropAfterMs: Long = DEFAULT_DROP_MS,
    private val flowWeight: Float = DEFAULT_FLOW_WEIGHT,
    private val maxSpeedRatio: Float = DEFAULT_MAX_SPEED_RATIO,
) {

    private class Track(
        var label: String,
        var score: Float,
        val motion: MotionModel,
        var lastSeenMs: Long,
    )

    private val lock = Any()
    private val tracks = LinkedHashMap<Int, Track>()

    /** Nombre d'ids suivis (avant purge). */
    val size: Int get() = synchronized(lock) { tracks.size }

    /** Efface tout : changement de concepts, nouvelle session, retour à l'écran. */
    fun reset() = synchronized(lock) { tracks.clear() }

    /**
     * Absorbe une réponse serveur datée de [timestampMs]. [frameWidth]/[frameHeight]
     * bornent la vitesse plausible (voir [MotionModel.observe]).
     */
    fun onServer(
        objects: List<ServerObject>,
        timestampMs: Long,
        frameWidth: Int,
        frameHeight: Int,
    ): SyncReport = synchronized(lock) {
        val maxSpeed = maxOf(frameWidth, 1) * maxSpeedRatio
        var created = 0
        var anchored = 0
        var jumped = 0
        var iouSum = 0f
        var iouCount = 0
        val seen = HashSet<Int>(objects.size)
        for (obj in objects) {
            if (obj.id in seen) continue
            seen += obj.id
            val track = tracks[obj.id]
            if (track == null) {
                tracks[obj.id] = Track(obj.label, obj.score, MotionModel(obj.box, timestampMs), timestampMs)
                created += 1
                continue
            }
            val predicted = track.motion.predictAt(timestampMs)
            val iou = obj.box.iou(predicted)
            val outcome = track.motion.observe(obj.box, timestampMs, maxSpeed)
            if (outcome == MotionModel.Outcome.JUMPED) jumped += 1 else anchored += 1
            iouSum += iou
            iouCount += 1
            track.label = obj.label
            track.score = obj.score
            track.lastSeenMs = timestampMs
        }
        val dropped = dropMissing(seen, timestampMs)
        val meanIou = if (iouCount == 0) 0f else iouSum / iouCount
        SyncReport(created, anchored, jumped, dropped, meanIou)
    }

    /**
     * Boîtes à afficher pour la frame caméra de [timestampMs]. [prev]/[cur] sont les
     * deux dernières images d'analyse en gris : quand elles sont fournies, le flot
     * recale la prédiction de chaque boîte (poids [flowWeight], sous le seuil de
     * confiance du block-matching rien n'est appliqué).
     */
    fun predictAt(
        timestampMs: Long,
        frameWidth: Int,
        frameHeight: Int,
        prev: GrayMask? = null,
        cur: GrayMask? = null,
    ): List<PredictedObject> = synchronized(lock) {
        tracks.entries
            .mapNotNull { (id, track) -> snapshot(id, track, timestampMs, frameWidth, frameHeight, prev, cur) }
            .sortedBy { it.id }
    }

    private fun snapshot(
        id: Int,
        track: Track,
        timestampMs: Long,
        frameWidth: Int,
        frameHeight: Int,
        prev: GrayMask?,
        cur: GrayMask?,
    ): PredictedObject? {
        val age = (timestampMs - track.lastSeenMs).coerceAtLeast(0L)
        if (age > maxCoastMs) return null
        val raw = track.motion.predictAt(timestampMs)
        val refined = refine(raw, prev, cur)
        return PredictedObject(
            id = id,
            label = track.label,
            score = track.score,
            box = refined.clampedTo(frameWidth, frameHeight),
            anchored = age <= ANCHOR_WINDOW_MS,
            confidence = track.motion.confidenceAt(timestampMs),
            ageMs = age,
        )
    }

    /** Correction de flot : partielle (poids), et seulement si le block-matching est sûr. */
    private fun refine(box: Box, prev: GrayMask?, cur: GrayMask?): Box {
        if (prev == null || cur == null) return box
        val shift = estimateShift(prev, cur, box) ?: return box
        if (!shift.trustworthy) return box
        return box.translated(shift.dx * flowWeight, shift.dy * flowWeight)
    }

    /** Purge les ids absents de la dernière réponse depuis plus de [dropAfterMs]. */
    private fun dropMissing(seen: Set<Int>, timestampMs: Long): Int {
        val stale = tracks.filterKeys { it !in seen }.filterValues { timestampMs - it.lastSeenMs > dropAfterMs }
        stale.forEach { (id, _) -> tracks.remove(id) }
        return stale.size
    }

    companion object {
        /** Durée de vol libre après la dernière observation (`predictAt` cesse ensuite). */
        const val DEFAULT_COAST_MS = 700L

        /** Au-delà, l'objet est oublié définitivement. */
        const val DEFAULT_DROP_MS = 1200L

        /** Part du décalage de flot appliquée : 0,6 corrige sans faire trembler. */
        const val DEFAULT_FLOW_WEIGHT = 0.6f

        /** Vitesse maximale plausible, en fraction de la largeur d'image par seconde. */
        const val DEFAULT_MAX_SPEED_RATIO = 0.9f

        /** Fenêtre où l'on considère la boîte comme « celle du serveur ». */
        const val ANCHOR_WINDOW_MS = 60L
    }
}