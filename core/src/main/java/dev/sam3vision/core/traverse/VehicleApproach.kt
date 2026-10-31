package dev.sam3vision.core.traverse

import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.MotionModel
import kotlin.math.abs

/**
 * Verdict d'approche : un véhicule fonce-t-il ([label] du plus dangereux) et
 * l'impact est-il dans [ttcSeconds] secondes (`null` sans approche) ?
 * [lateralTtcSeconds] expose le temps-avant-croisement latéral (largeur /
 * vitesse latérale, `null` sans latéral franc) : informatif seul, il ne
 * déclenche jamais — le veto le seuille (autre module).
 */
data class ApproachVerdict(
    val approaching: Boolean,
    val ttcSeconds: Float?,
    val label: String?,
    val lateralTtcSeconds: Float? = null,
)

/**
 * Approche véhicules sur boîtes SAM : le direct `/live` ne donnant pas d'ids
 * stables, l'association se fait ici par IoU entre frames, puis chaque piste
 * porte un [MotionModel] réutilisé (vitesse de largeur lissée) dont on tire
 * le temps-avant-collision tau = largeur / vitesse (Lee 1976), plus le
 * TTC latéral (largeur / vitesse latérale) exposé au veto et la vitesse
 * radiale Kalman consommée en interne (confirmation, jamais suppression).
 * Pistes par instance (clé label + seau de position, voir [instanceKeyOf]),
 * purge à [STALE_MS] avec réactivation courte ([REACTIVATE_MS]).
 *
 * Seuils = priors à recaler sur téléphone ([TTC_SECONDS] & co), direction
 * sûre : une piste jeune, floue ou perdue ne déclenche jamais — seul un
 * rapprochement franc et confirmé force le veto. Pur Kotlin testé en JVM.
 */
class VehicleApproach {

    private class Track(
        var label: String,
        val motion: MotionModel,
        var lastSeenMs: Long,
    )

    private data class VehicleBox(val label: String, val box: Box)

    private data class DroppedTrack(val track: Track, val droppedAtMs: Long)

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 0

    private val dropped = LinkedHashMap<Int, DroppedTrack>()

    /** Pistes en cours (après purge) — exposé pour les tests. */
    val trackCount: Int get() = tracks.size

    /** Verdicts fond de la dernière frame par piste (vide sans signal fond). */
    var lastVerdicts: Map<Int, MovingVerdict> = emptyMap()
        private set

    /**
     * Vrai quand chaque piste évaluée accuse la caméra ([Verdict.CAMERA_MOVED])
     * : le porteur bouge, aucun véhicule ne roule — le veto mouvement se tait.
     * Faux sans piste évaluée : on ne met jamais le veto à l'aveugle.
     */
    var cameraMoved: Boolean = false
        private set

    /**
     * Clés d'instance ([instanceKeyOf]) dont chaque piste évaluée accuse
     * la caméra : leurs croissances se taisent. Une clé sans piste évaluée
     * (piste jeune) n'y figure jamais — on ne tait que ce qu'on a jugé.
     */
    var cameraMovedKeys: Set<String> = emptySet()
        private set

    /** Oublie toutes les pistes (nouvelle traversée). */
    fun reset() {
        tracks.clear()
        nextId = 0
        dropped.clear()
        lastVerdicts = emptyMap()
        cameraMoved = false
        cameraMovedKeys = emptySet()
    }

    /**
     * Verdict pour une frame datée de [atMs] (ms, monotone). [motion]
     * (`null` = pas de signal fond) fait juger chaque piste par
     * [VehicleBackground] : une piste qui suit le défilement du fond
     * ([Verdict.CAMERA_MOVED]) ne conclut plus l'approche — les pistes
     * restent suivies, seul le verdict se tait. [radialMpsByKey] porte les
     * vitesses radiales Kalman (m/s, négatif en approche) par clé d'instance
     * (`null` = pas de signal distance : comportement inchangé) : une radiale
     * franche confirme l'approche, jamais elle ne supprime un tau.
     */
    fun update(
        detections: List<Detection>,
        atMs: Long,
        motion: FrameMotion? = null,
        radialMpsByKey: Map<String, Float>? = null,
    ): ApproachVerdict {
        val frameWidth = detections.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        val frameHeight = detections.maxOfOrNull { it.height }?.takeIf { it > 0 } ?: FALLBACK_HEIGHT
        associate(vehiclesOf(detections), atMs, frameWidth)
        dropStale(atMs)
        evaluateMotion(motion, frameWidth, frameHeight)
        return verdict(frameWidth, radialMpsByKey)
    }

    /**
     * Verdicts fond des pistes à vitesse établie : la vitesse lissée de la
     * piste (px/s) passe en fractions d'image par frame pour [VehicleBackground].
     * Sans [motion], verdicts vides et [cameraMoved] faux (comportement inchangé).
     */
    private fun evaluateMotion(motion: FrameMotion?, frameWidth: Int, frameHeight: Int) {
        if (motion == null) {
            lastVerdicts = emptyMap()
            cameraMoved = false
            cameraMovedKeys = emptySet()
            return
        }
        val dtSeconds = motion.dtMs / 1000f
        lastVerdicts = tracks.mapNotNull { (id, track) ->
            if (!track.motion.velocityReliable) return@mapNotNull null
            val boxDx = track.motion.velocity.x / frameWidth * dtSeconds
            val boxDy = track.motion.velocity.y / frameHeight * dtSeconds
            id to VehicleBackground.decide(id, boxDx, boxDy, motion.global, motion.lumaWidth, motion.lumaHeight, motion.dtMs, 0f)
        }.toMap()
        cameraMoved = lastVerdicts.isNotEmpty() && lastVerdicts.all { it.value.verdict == Verdict.CAMERA_MOVED }
        cameraMovedKeys = lastVerdicts.entries
            .groupBy { entry ->
                val track = tracks.getValue(entry.key)
                instanceKeyOf(track.label, track.motion.anchorBox.centerX / frameWidth)
            }
            .filterValues { group -> group.all { it.value.verdict == Verdict.CAMERA_MOVED } }
            .keys
    }

    /** Véhicules confiants à boîte saine, seuls suivis. */
    private fun vehiclesOf(detections: List<Detection>): List<VehicleBox> =
        detections.mapNotNull { detection ->
            if (!VehicleConcepts.isVehicle(detection.label)) return@mapNotNull null
            if (detection.score <= TraverseVeto.SCORE_MIN) return@mapNotNull null
            val box = Box.fromProto(detection.box) ?: return@mapNotNull null
            if (!box.isSane) return@mapNotNull null
            VehicleBox(detection.label, box)
        }

    /** Associe chaque véhicule à sa piste (meilleur IoU) ou en crée une. */
    private fun associate(vehicles: List<VehicleBox>, atMs: Long, frameWidth: Int) {
        val maxSpeed = frameWidth * BoxPredictor.DEFAULT_MAX_SPEED_RATIO
        val claimed = HashSet<Int>()
        for (vehicle in vehicles) {
            val id = bestTrack(vehicle.box, atMs, claimed)
                ?: bestDropped(vehicle.box, atMs, claimed)
            if (id == null) {
                tracks[nextId] = Track(vehicle.label, MotionModel(vehicle.box, atMs), atMs)
                nextId += 1
            } else if (id in dropped) {
                claimed += id
                val revived = dropped.remove(id)!!
                revived.track.label = vehicle.label
                revived.track.motion.observe(vehicle.box, atMs, maxSpeed)
                revived.track.lastSeenMs = atMs
                tracks[id] = revived.track
            } else {
                claimed += id
                val track = tracks.getValue(id)
                track.label = vehicle.label
                track.motion.observe(vehicle.box, atMs, maxSpeed)
                track.lastSeenMs = atMs
            }
        }
    }

    /** Piste prédite la plus proche, `null` sous le seuil IoU ou déjà prise. */
    private fun bestTrack(box: Box, atMs: Long, claimed: Set<Int>): Int? {
        var bestId: Int? = null
        var bestIou = 0f
        for ((id, track) in tracks) {
            if (id in claimed) continue
            val iou = box.iou(track.motion.predictAt(atMs))
            if (iou > bestIou) {
                bestIou = iou
                bestId = id
            }
        }
        if (bestIou < IOU_MIN) return null
        return bestId
    }

    /** Piste purgée la plus proche dans la fenêtre de réactivation, `null` sinon. */
    private fun bestDropped(box: Box, atMs: Long, claimed: Set<Int>): Int? {
        var bestId: Int? = null
        var bestIou = 0f
        for ((id, entry) in dropped) {
            if (id in claimed) continue
            if (atMs - entry.droppedAtMs > REACTIVATE_MS) continue
            val iou = box.iou(entry.track.motion.predictAt(atMs))
            if (iou > bestIou) {
                bestIou = iou
                bestId = id
            }
        }
        if (bestIou < IOU_MIN) return null
        return bestId
    }

    /** Oublie les pistes sans observation récente (objet sorti ou perdu). */
    private fun dropStale(atMs: Long) {
        val stale = tracks.filterValues { atMs - it.lastSeenMs > STALE_MS }.keys
        stale.forEach { id ->
            dropped[id] = DroppedTrack(tracks.getValue(id), atMs)
            tracks.remove(id)
        }
        dropped.entries.removeAll { atMs - it.value.droppedAtMs > REACTIVATE_MS }
    }

    /** Le plus dangereux des rapprochements francs, silence sinon. */
    private fun verdict(frameWidth: Int, radialMpsByKey: Map<String, Float>?): ApproachVerdict {
        var bestLabel: String? = null
        var bestTtc = Float.MAX_VALUE
        var radialLabel: String? = null
        var radialBest = Float.MAX_VALUE
        var lateralTtc: Float? = null
        for ((id, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            if (lastVerdicts[id]?.verdict == Verdict.CAMERA_MOVED) continue
            val width = track.motion.anchorBox.width
            if (width < frameWidth * MIN_WIDTH_FRACTION) continue
            lateralOf(track, width, frameWidth)?.let { ttc ->
                if (lateralTtc == null || ttc < lateralTtc!!) lateralTtc = ttc
            }
            radialHit(track, frameWidth, radialMpsByKey)?.let { radial ->
                if (radial < radialBest) {
                    radialBest = radial
                    radialLabel = track.label
                }
            }
            val growth = track.motion.velocity.width
            if (growth > frameWidth * MIN_GROWTH_RATIO) {
                val tau = width / growth
                if (tau.isFinite() && tau < bestTtc) {
                    bestTtc = tau
                    bestLabel = track.label
                }
            }
        }
        if (bestLabel != null && bestTtc < TTC_SECONDS) return ApproachVerdict(true, bestTtc, bestLabel, lateralTtc)
        if (radialLabel != null) return ApproachVerdict(true, null, radialLabel, lateralTtc)
        return ApproachVerdict(false, null, null, lateralTtc)
    }

    /** TTC latéral d'une piste (largeur / |vitesse x|), `null` sans latéral franc. */
    private fun lateralOf(track: Track, width: Float, frameWidth: Int): Float? {
        val lateral = abs(track.motion.velocity.x)
        if (lateral <= frameWidth * MIN_LATERAL_RATIO) return null
        return (width / lateral).takeIf { it.isFinite() }
    }

    /** Vitesse radiale d'une piste par sa clé d'instance, `null` sans signal franc. */
    private fun radialHit(track: Track, frameWidth: Int, radialMpsByKey: Map<String, Float>?): Float? {
        val radial = radialMpsByKey?.get(instanceKeyOf(track.label, track.motion.anchorBox.centerX / frameWidth))
        return radial?.takeIf { it.isFinite() && it <= RADIAL_APPROACH_MPS }
    }

    companion object {
        /** Sous ce temps-avant-collision (s), le véhicule force le veto. */
        const val TTC_SECONDS = 3.0f

        /** IoU minimal pour rattacher une boîte à une piste. */
        const val IOU_MIN = 0.2f

        /** Au-delà sans observation, la piste est oubliée. */
        const val STALE_MS = 1500L

        /** Largeur minimale d'une boîte prise au sérieux (fraction d'image). */
        const val MIN_WIDTH_FRACTION = 0.02f

        /** Croissance minimale (fraction d'image/s) : en dessous, c'est du bruit. */
        const val MIN_GROWTH_RATIO = 0.005f

        /** Largeur d'image de repli quand les détections ne la donnent pas. */
        const val FALLBACK_WIDTH = 1000

        /** Hauteur d'image de repli (même rôle pour la dérive verticale). */
        const val FALLBACK_HEIGHT = 1000

        /** Piste purgée réactivable si elle réapparaît dans ce délai (retour rapide). */
        const val REACTIVATE_MS = 500L

        /** Vitesse radiale (m/s) sous laquelle l'approche est confirmée par la distance. */
        const val RADIAL_APPROACH_MPS = -1.0f

        /** Vitesse latérale minimale (fraction d'image/s) pour un TTC latéral. */
        const val MIN_LATERAL_RATIO = 0.01f

        /**
         * Clé d'instance d'un véhicule : label canonique + seau de position
         * stable (gauche/centre/droite, frontières [PositionText.SIDE_EDGE]) —
         * deux voitures à deux endroits ne fusionnent plus. [centerX01] hors
         * bornes ou non fini = centre (doute stable).
         */
        fun instanceKeyOf(label: String, centerX01: Float): String {
            val x = centerX01.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0.5f
            val bucket = when {
                x < PositionText.SIDE_EDGE -> "L"
                x > 1f - PositionText.SIDE_EDGE -> "R"
                else -> "C"
            }
            return "${VehicleConcepts.keyOf(label)}@$bucket"
        }
    }
}
