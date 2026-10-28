package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.distance.Pinhole
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SegObject

/** Ancre de calibration : mesure sténopé et sa proximité médiane. */
data class DepthAnchor(val meters: Float, val closeness: Float)

/**
 * Distances par profondeur monoculaire : médiane de proximité par boîte,
 * calibrée en mètres par les ancres sténopé de la frame. Pur JVM.
 *
 * Le sténopé ne mesure que les labels à hauteur connue ; la carte mesure
 * tout ce qui est cadré (trottoirs, murs, poteaux) dès qu'une ancre fixe
 * l'échelle. Sans ancre ni proximité : le sténopé est gardé tel quel.
 */
object DepthReadings {

    /**
     * Médiane de proximité des pixels finis de [box] (pixels image 0..1
     * ramenés à la carte) ; `null` si la boîte est vide ou hors champ.
     */
    fun boxCloseness(depth: DepthMap, box: List<Float>, frameW: Int, frameH: Int): Float? {
        if (box.size < 4 || frameW <= 0 || frameH <= 0) return null
        if (depth.width <= 0 || depth.height <= 0) return null
        if (box[2] <= 0f || box[0] >= frameW || box[3] <= 0f || box[1] >= frameH) return null
        val x1 = ((box[0] / frameW) * depth.width).toInt().coerceIn(0, depth.width - 1)
        val x2 = ((box[2] / frameW) * depth.width).toInt().coerceIn(x1, depth.width - 1)
        val y1 = ((box[1] / frameH) * depth.height).toInt().coerceIn(0, depth.height - 1)
        val y2 = ((box[3] / frameH) * depth.height).toInt().coerceIn(y1, depth.height - 1)
        val kept = FloatArray((x2 - x1 + 1) * (y2 - y1 + 1))
        var count = 0
        for (y in y1..y2) {
            for (x in x1..x2) {
                val v = depth.values[y * depth.width + x]
                if (v.isFinite()) {
                    kept[count++] = v
                }
            }
        }
        if (count == 0) return null
        return DepthFusion.quickselectMedian(kept, count)
    }

    /**
     * Échelle mètres : médiane des (mètres × proximité) des ancres ;
     * `null` sans ancre exploitable.
     */
    fun scaleFromAnchors(anchors: List<DepthAnchor>): Float? {
        val scales = FloatArray(anchors.size)
        var count = 0
        for (anchor in anchors) {
            val scale = DepthFusion.calibrateScale(anchor.meters, anchor.closeness)
            if (scale != null) {
                scales[count++] = scale
            }
        }
        if (count == 0) return null
        return DepthFusion.quickselectMedian(scales, count)
    }

    /**
     * Lectures affinées, dans l'ordre des détections : la profondeur remplace
     * le sténopé quand elle mesure (même sans hauteur connue), l'ajoute quand
     * le sténopé est muet, et le garde sinon. Ni l'un ni l'autre : omis.
     */
    fun refine(
        frameW: Int,
        frameH: Int,
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        depth: DepthMap,
    ): List<DistanceReading> {
        val closeByBox = HashMap<List<Float>, Float>()
        for (detection in detections) {
            val closeness = boxCloseness(depth, detection.box, frameW, frameH)
            if (closeness != null) {
                closeByBox[detection.box] = closeness
            }
        }
        return refineWithCloseness(detections, pinhole, closeByBox)
    }

    /**
     * Proximités mesurées par le serveur (`with_depth`) : médiane par boîte,
     * finies seulement — une valeur absente ou non finie ne mesure rien.
     */
    fun serverCloseness(objects: List<SegObject>): Map<List<Float>, Float> {
        val closeByBox = HashMap<List<Float>, Float>()
        for (obj in objects) {
            val closeness = obj.depth?.takeIf { it.isFinite() } ?: continue
            closeByBox[obj.box] = closeness
        }
        return closeByBox
    }

    /**
     * Même affinage que [refine], sur des proximités déjà mesurées (serveur) :
     * calibration par les ancres sténopé de la frame, puis remplace/ajoute/garde.
     * L'échelle restant calibrée par frame, la normalisation serveur (0..1) et la
     * carte brute embarquée se valent ici.
     */
    fun refineWithCloseness(
        detections: List<Detection>,
        pinhole: List<DistanceReading>,
        closeByBox: Map<List<Float>, Float>,
    ): List<DistanceReading> {
        val byBox = pinhole.associateBy { it.box }
        val anchors = ArrayList<DepthAnchor>(pinhole.size)
        for (reading in pinhole) {
            val closeness = closeByBox[reading.box]
            val meters = reading.meters.takeIf { it.isFinite() }
            if (closeness != null && meters != null) {
                anchors += DepthAnchor(meters, closeness)
            }
        }
        val scale = scaleFromAnchors(anchors)
        val refined = ArrayList<DistanceReading>(detections.size)
        for (detection in detections) {
            refined += refinedOne(detection, byBox[detection.box], closeByBox[detection.box], scale)
                ?: continue
        }
        return refined
    }

    private fun refinedOne(
        detection: Detection,
        base: DistanceReading?,
        closeness: Float?,
        scale: Float?,
    ): DistanceReading? {
        var meters: Float? = null
        if (closeness != null && scale != null) {
            meters = toMeters(closeness, scale)
        }
        if (meters != null) {
            val id = base?.trackId ?: DistanceMeter.UNTRACKED_ID
            return DistanceReading(id, detection.label, meters, meters, detection.box)
        }
        return base
    }

    /** Proximité → mètres bornés plausibles ; `null` si hors bornes. */
    private fun toMeters(closeness: Float, scale: Float): Float? {
        val meters = DepthFusion.closenessToMeters(closeness, scale) ?: return null
        if (!meters.isFinite()) return null
        return meters.coerceIn(Pinhole.MIN_METERS, Pinhole.MAX_METERS)
    }
}
