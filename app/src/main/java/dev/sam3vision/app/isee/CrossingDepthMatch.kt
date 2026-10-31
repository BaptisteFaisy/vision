package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingOffer
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.predict.Box

/**
 * Rattachement profondeur d'une boîte de passage : la lecture exacte d'abord,
 * sinon la meilleure lecture « passage » recouvrante (repli IoU — les boîtes
 * SAM et profondeur ne coïncident pas toujours au pixel près).
 *
 * Sorti de `CrossingWiring.kt` pour le laisser sous la limite de 500 lignes.
 */
internal fun crosswalkDepthMeters(readings: List<DistanceReading>, box: List<Float>): Float? {
    readings.firstOrNull { it.box == box }
        ?.meters?.takeIf { it.isFinite() && it > 0f }
        ?.let { return it }
    return readings
        .filter { CrossingOffer.isCrosswalkLabel(it.label) }
        .mapNotNull { reading ->
            val meters = reading.meters.takeIf { it.isFinite() && it > 0f } ?: return@mapNotNull null
            iouOf(reading.box, box) to meters
        }
        .filter { it.first >= CROSSING_BOX_MATCH_IOU }
        .maxByOrNull { it.first }
        ?.second
}

/** Recouvrement min pour rattacher une lecture au passage (repli IoU). */
internal const val CROSSING_BOX_MATCH_IOU = 0.3f

private fun iouOf(a: List<Float>, b: List<Float>): Float {
    val first = Box.fromProto(a) ?: return 0f
    val second = Box.fromProto(b) ?: return 0f
    return first.iou(second)
}
