package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.predict.Box
import dev.sam3vision.core.predict.BoxPredictor
import dev.sam3vision.core.predict.MotionModel

/**
 * Verdict d'approche : un véhicule fonce-t-il ([label] du plus dangereux) et
 * l'impact est-il dans [ttcSeconds] secondes (`null` sans approche) ?
 */
data class ApproachVerdict(
    val approaching: Boolean,
    val ttcSeconds: Float?,
    val label: String?,
)

/**
 * Approche véhicules sur boîtes SAM : le direct `/live` ne donnant pas d'ids
 * stables, l'association se fait ici par IoU entre frames, puis chaque piste
 * porte un [MotionModel] réutilisé (vitesse de largeur lissée) dont on tire
 * le temps-avant-collision tau = largeur / vitesse (Lee 1976).
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

    private val tracks = LinkedHashMap<Int, Track>()
    private var nextId = 0

    /** Pistes en cours (après purge) — exposé pour les tests. */
    val trackCount: Int get() = tracks.size

    /** Oublie toutes les pistes (nouvelle traversée). */
    fun reset() {
        tracks.clear()
        nextId = 0
    }

    /** Verdict pour une frame datée de [atMs] (ms, monotone). */
    fun update(detections: List<Detection>, atMs: Long): ApproachVerdict {
        val frameWidth = detections.maxOfOrNull { it.width }?.takeIf { it > 0 } ?: FALLBACK_WIDTH
        associate(vehiclesOf(detections), atMs, frameWidth)
        dropStale(atMs)
        return verdict(frameWidth)
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
            if (id == null) {
                tracks[nextId] = Track(vehicle.label, MotionModel(vehicle.box, atMs), atMs)
                nextId += 1
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

    /** Oublie les pistes sans observation récente (objet sorti ou perdu). */
    private fun dropStale(atMs: Long) {
        val stale = tracks.filterValues { atMs - it.lastSeenMs > STALE_MS }.keys
        stale.forEach { tracks.remove(it) }
    }

    /** Le plus dangereux des rapprochements francs, silence sinon. */
    private fun verdict(frameWidth: Int): ApproachVerdict {
        var bestLabel: String? = null
        var bestTtc = Float.MAX_VALUE
        for ((_, track) in tracks) {
            if (!track.motion.velocityReliable) continue
            val width = track.motion.anchorBox.width
            if (width < frameWidth * MIN_WIDTH_FRACTION) continue
            if (track.motion.velocity.width <= frameWidth * MIN_GROWTH_RATIO) continue
            val tau = width / track.motion.velocity.width
            if (tau < bestTtc) {
                bestTtc = tau
                bestLabel = track.label
            }
        }
        if (bestLabel == null || bestTtc >= TTC_SECONDS) return ApproachVerdict(false, null, null)
        return ApproachVerdict(true, bestTtc, bestLabel)
    }

    companion object {
        /** Sous ce temps-avant-collision (s), le véhicule force le veto. */
        const val TTC_SECONDS = 3.0f

        /** IoU minimal pour rattacher une boîte à une piste. */
        const val IOU_MIN = 0.3f

        /** Au-delà sans observation, la piste est oubliée. */
        const val STALE_MS = 1000L

        /** Largeur minimale d'une boîte prise au sérieux (fraction d'image). */
        const val MIN_WIDTH_FRACTION = 0.02f

        /** Croissance minimale (fraction d'image/s) : en dessous, c'est du bruit. */
        const val MIN_GROWTH_RATIO = 0.005f

        /** Largeur d'image de repli quand les détections ne la donnent pas. */
        const val FALLBACK_WIDTH = 1000
    }
}
