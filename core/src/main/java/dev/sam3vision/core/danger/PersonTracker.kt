package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection

/**
 * Une personne suivie entre les frames : identité stable ([id]), boîte
 * normalisée 0..1 (lissée par Kalman), âge ([frames] vues). Le tracker ne voit
 * que des boîtes — l'approche se lit dans leur grossissement.
 */
data class DangerTrack(
    val id: Int,
    val label: String,
    val score: Float,
    val box: List<Float>,
    val frames: Int,
    val missed: Int,
    val firstAtMs: Long,
    val lastAtMs: Long,
) {
    /** Largeur normalisée de la boîte. */
    val width: Float get() = box[2] - box[0]

    /** Hauteur normalisée de la boîte. */
    val height: Float get() = box[3] - box[1]

    /** Aire normalisée (0..1) : ce qui grossit quand on s'approche. */
    val area: Float get() = (width * height).coerceAtLeast(0f)

    /** Centre horizontal normalisé (0,5 = plein champ). */
    val centerX: Float get() = (box[0] + box[2]) / 2f

    /** Centre vertical normalisé. */
    val centerY: Float get() = (box[1] + box[3]) / 2f
}

/**
 * Recouvrement de deux boîtes normalisées `[x1,y1,x2,y2]` (0 = disjointes,
 * 1 = identiques). Pur : même entrées, même sortie.
 */
fun boxIou(a: List<Float>, b: List<Float>): Float {
    if (a.size < 4 || b.size < 4) return 0f
    val x1 = maxOf(a[0], b[0])
    val y1 = maxOf(a[1], b[1])
    val x2 = minOf(a[2], b[2])
    val y2 = minOf(a[3], b[3])
    val inter = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
    if (inter <= 0f) return 0f
    val union = boxArea(a) + boxArea(b) - inter
    if (union <= 0f) return 0f
    return (inter / union).coerceIn(0f, 1f)
}

/** Aire d'une boîte normalisée, 0 si dégénérée. */
fun boxArea(box: List<Float>): Float {
    if (box.size < 4) return 0f
    return ((box[2] - box[0]).coerceAtLeast(0f) * (box[3] - box[1]).coerceAtLeast(0f))
}

/**
 * Seuils ByteTrack (défauts du `bytetrack.yaml` ultralytics 8.4, lus en
 * référence) : partage haute/basse confiance, création, mémoire des perdues,
 * plafond d'appariement et fusion du score.
 */
data class ByteTrackParams(
    val highThresh: Float = 0.25f,
    val lowThresh: Float = 0.1f,
    val newThresh: Float = 0.25f,
    val maxLostFrames: Int = 30,
    val matchThresh: Float = 0.8f,
    val fuseScore: Boolean = true,
)

/** État d'une piste : nouvelle, suivie, perdue (réactivable) ou supprimée. */
private enum class TrackState { New, Tracked, Lost, Removed }

/**
 * Suivi multi-personnes façon ByteTrack (papier ByteTrack + lecture du
 * `BYTETracker` ultralytics, réimplémenté en Kotlin propre — pas de code
 * copié) : prédiction Kalman à vitesse constante, association en deux temps
 * (boîtes fortes puis faibles, Hongrois sur coût IoU), pistes perdues
 * réactivables ([maxLostFrames]), déduplication suivi/perdu.
 *
 * Pur Kotlin, sans Android : les boîtes entrent en pixels ([Detection]), les
 * pistes sortent normalisées 0..1, boîte lissée par Kalman (l'approche y est
 * plus stable que sur la mesure brute). Seules les pistes confirmées sortent
 * (une piste d'une seule frame ne parle pas).
 */
class PersonTracker(
    private val params: ByteTrackParams = ByteTrackParams(),
) {
    private class Tracklet(
        val id: Int,
        var state: KalmanXyah.State,
        var trackState: TrackState,
        var activated: Boolean,
        var score: Float,
        var label: String,
        var hits: Int,
        val startFrame: Int,
        var endFrame: Int,
        val firstAtMs: Long,
        var lastAtMs: Long,
    )

    private var frameId = 0
    private var nextId = 1
    private val tracked = mutableListOf<Tracklet>()
    private val lost = mutableListOf<Tracklet>()
    private var lastOutputs: List<DangerTrack> = emptyList()

    /**
     * Une frame de détections → les pistes confirmées à jour. Chaque appel
     * vaut une frame (le compteur Kalman avance) ; [nowMs] horodate.
     */
    fun update(detections: List<Detection>, nowMs: Long): List<DangerTrack> {
        frameId++
        val boxes = detections.mapNotNull { it.normalizedBox()?.let { box -> it to clamp(box) } }
        val (high, low) = splitByScore(boxes)
        val pool = (tracked.filter { it.activated } + lost).onEach { predict(it) }
        val first = associate(pool, high, params.matchThresh, fuse = params.fuseScore)
        applyMatches(pool, high, first.matches, nowMs)
        val stillTracked = first.unmatchedRows.map { pool[it] }.filter { it.trackState == TrackState.Tracked }
        val second = associate(stillTracked, low, SECOND_THRESH, fuse = false)
        applyMatches(stillTracked, low, second.matches, nowMs)
        markLost(stillTracked, second.unmatchedRows)
        val leftHigh = first.unmatchedCols.mapTo(mutableListOf()) { high[it] }
        matchUnconfirmed(leftHigh, nowMs)
        activateNew(leftHigh, nowMs)
        dropStaleLost()
        dedup()
        lastOutputs = tracked
            .filter { it.trackState == TrackState.Tracked && it.activated }
            .map { toDangerTrack(it, nowMs) }
        return lastOutputs
    }

    /** Pistes sorties à la dernière frame (lecture seule). */
    fun alive(): List<DangerTrack> = lastOutputs

    /** Oublie tout, ids repartis à 1 (nouvelle séance). */
    fun reset() {
        frameId = 0
        nextId = 1
        tracked.clear()
        lost.clear()
        lastOutputs = emptyList()
    }

    /** Fortes (≥ seuil haut) contre faibles (> seuil bas, < seuil haut), boîtes valides. */
    private fun splitByScore(
        boxes: List<Pair<Detection, List<Float>>>,
    ): Pair<List<Pair<Detection, List<Float>>>, List<Pair<Detection, List<Float>>>> {
        val valid = boxes.filter { boxArea(it.second) > 0f }
        return valid.filter { it.first.score >= params.highThresh } to
            valid.filter { it.first.score > params.lowThresh && it.first.score < params.highThresh }
    }

    /** Prédiction Kalman (une piste perdue ne grandit plus : vitesse de hauteur nulle). */
    private fun predict(tracklet: Tracklet) {
        if (tracklet.trackState != TrackState.Tracked) tracklet.state.mean[7] = 0.0
        tracklet.state = KalmanXyah.predict(tracklet.state)
    }

    /** Appariement Hongrois sur coût IoU (fusionné au score ou non), filtré au seuil. */
    private fun associate(
        tracklets: List<Tracklet>,
        dets: List<Pair<Detection, List<Float>>>,
        thresh: Float,
        fuse: Boolean,
    ): Assignment {
        // Côté vide : rien à apparier, tout le monde est seul (la matrice vide
        // perdrait le compte des colonnes — le Hongrois ne le devine pas).
        if (tracklets.isEmpty() || dets.isEmpty()) {
            return Assignment(emptyList(), tracklets.indices.toList(), dets.indices.toList())
        }
        val cost = Array(tracklets.size) { r ->
            DoubleArray(dets.size) { c -> matchCost(boxOf(tracklets[r]), dets[c].second, dets[c].first.score, fuse) }
        }
        return Hungarian.assign(cost, thresh.toDouble())
    }

    /** Coût 1 − IoU, fusionné au score (1 − IoU·score) quand demandé. */
    private fun matchCost(trackBox: List<Float>, detBox: List<Float>, score: Float, fuse: Boolean): Double {
        val iou = boxIou(trackBox, detBox).toDouble()
        return if (fuse) 1.0 - iou * score else 1.0 - iou
    }

    /** Paires appariées : mise à jour Kalman (ou réactivation), listes à jour. */
    private fun applyMatches(
        pool: List<Tracklet>,
        dets: List<Pair<Detection, List<Float>>>,
        matches: List<Pair<Int, Int>>,
        nowMs: Long,
    ) {
        for ((r, c) in matches) {
            val tracklet = pool[r]
            val (detection, box) = dets[c]
            tracklet.state = KalmanXyah.update(tracklet.state, toXyah(box))
            tracklet.trackState = TrackState.Tracked
            tracklet.activated = true
            tracklet.score = detection.score
            tracklet.label = detection.label
            tracklet.hits++
            tracklet.endFrame = frameId
            tracklet.lastAtMs = nowMs
            if (tracklet in lost) {
                lost.remove(tracklet)
                if (tracklet !in tracked) tracked.add(tracklet)
            }
        }
    }

    /** Non-appariées du 2e temps : perdues (réactivables pendant [maxLostFrames]). */
    private fun markLost(stillTracked: List<Tracklet>, unmatched: List<Int>) {
        for (i in unmatched) {
            val tracklet = stillTracked[i]
            tracklet.trackState = TrackState.Lost
            tracked.remove(tracklet)
            lost.add(tracklet)
        }
    }

    /**
     * Pistes non confirmées (une seule vue) contre fortes restantes : seuil
     * 0,7 fusionné ; les solitaires sont supprimées (faux positif probable).
     */
    private fun matchUnconfirmed(leftHigh: MutableList<Pair<Detection, List<Float>>>, nowMs: Long) {
        val unconfirmed = tracked.filter { !it.activated }
        if (unconfirmed.isEmpty()) return
        val found = associate(unconfirmed, leftHigh, UNCONFIRMED_THRESH, fuse = params.fuseScore)
        applyMatches(unconfirmed, leftHigh, found.matches, nowMs)
        for (i in found.unmatchedRows) {
            val tracklet = unconfirmed[i]
            tracklet.trackState = TrackState.Removed
            tracked.remove(tracklet)
        }
        leftHigh.removeMatched(found)
    }

    /** Fortes orphelines ≥ seuil de création : nouvelles pistes (confirmées à la 2e vue). */
    private fun activateNew(leftHigh: List<Pair<Detection, List<Float>>>, nowMs: Long) {
        for ((detection, box) in leftHigh.filter { it.first.score >= params.newThresh }) {
            tracked.add(
                Tracklet(
                    id = nextId++, state = KalmanXyah.initiate(toXyah(box)), trackState = TrackState.Tracked,
                    activated = frameId == 1, score = detection.score, label = detection.label,
                    hits = 1, startFrame = frameId, endFrame = frameId, firstAtMs = nowMs, lastAtMs = nowMs,
                ),
            )
        }
    }

    /** Perdues trop vieilles : supprimées (fin de mémoire d'occlusion). */
    private fun dropStaleLost() {
        lost.removeAll { frameId - it.endFrame > params.maxLostFrames }
    }

    /** Doublons suivi/perdu (IoU > 0,85) : la plus jeune des deux est jetée. */
    private fun dedup() {
        val dropTracked = mutableSetOf<Tracklet>()
        val dropLost = mutableSetOf<Tracklet>()
        for (a in tracked) {
            for (b in lost) {
                if (1.0 - boxIou(boxOf(a), boxOf(b)) >= DUP_THRESH) continue
                if (a.endFrame - a.startFrame > b.endFrame - b.startFrame) dropLost.add(b) else dropTracked.add(a)
            }
        }
        tracked.removeAll(dropTracked)
        lost.removeAll(dropLost)
    }

    /** Boîte Kalman (centre, aspect, hauteur) → normalisée x1,y1,x2,y2 bornée. */
    private fun boxOf(tracklet: Tracklet): List<Float> {
        val mean = tracklet.state.mean
        val w = (mean[2] * mean[3]).toFloat()
        val h = mean[3].toFloat()
        return clamp(listOf((mean[0] - w / 2).toFloat(), (mean[1] - h / 2).toFloat(), (mean[0] + w / 2).toFloat(), (mean[1] + h / 2).toFloat()))
    }

    private fun toDangerTrack(tracklet: Tracklet, nowMs: Long): DangerTrack =
        DangerTrack(tracklet.id, tracklet.label, tracklet.score, boxOf(tracklet), tracklet.hits, 0, tracklet.firstAtMs, nowMs)

    private fun toXyah(box: List<Float>): DoubleArray {
        val w = (box[2] - box[0]).toDouble()
        val h = (box[3] - box[1]).toDouble()
        return doubleArrayOf(((box[0] + box[2]) / 2).toDouble(), ((box[1] + box[3]) / 2).toDouble(), w / h, h)
    }

    private fun clamp(box: List<Float>): List<Float> =
        listOf(box[0].coerceIn(0f, 1f), box[1].coerceIn(0f, 1f), box[2].coerceIn(0f, 1f), box[3].coerceIn(0f, 1f))

    companion object {
        /** Plafond du 2e temps (faibles, IoU seule) : coût 0,5. */
        const val SECOND_THRESH = 0.5f

        /** Plafond des non-confirmées (fusionné) : coût 0,7. */
        const val UNCONFIRMED_THRESH = 0.7f

        /** Doublons : distance IoU < 0,15 (recouvrement > 0,85). */
        const val DUP_THRESH = 0.15f
    }
}

/** Retire les détections consommées par les non-confirmées (reste les orphelines). */
private fun MutableList<Pair<Detection, List<Float>>>.removeMatched(found: Assignment) {
    val used = found.matches.map { it.second }.toSet()
    val keep = this.filterIndexed { index, _ -> index !in used }
    this.clear()
    this.addAll(keep)
}
