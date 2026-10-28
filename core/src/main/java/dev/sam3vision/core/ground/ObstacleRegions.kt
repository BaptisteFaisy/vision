package dev.sam3vision.core.ground

import dev.sam3vision.core.depth.DepthFusion
import kotlin.math.abs
import kotlin.math.ceil

/** Nature d'une région : obstacle au-dessus du sol, ou vide sous le plan. */
enum class RegionType {
    OBSTACLE,
    DROPOFF,
}

/** Boîte englobante en lignes/colonnes (`(y_min, y_max, x_min, x_max)` kinbn). */
data class ObstacleBox(
    val yMin: Int,
    val yMax: Int,
    val xMin: Int,
    val xMax: Int,
) {
    /** Intersection-sur-union (`_bbox_iou`). */
    fun iou(other: ObstacleBox): Float {
        val y1 = maxOf(yMin, other.yMin)
        val y2 = minOf(yMax, other.yMax)
        val x1 = maxOf(xMin, other.xMin)
        val x2 = minOf(xMax, other.xMax)
        val inter = maxOf(0, y2 - y1) * maxOf(0, x2 - x1)
        if (inter == 0) return 0f
        val union = (yMax - yMin) * (xMax - xMin) +
            (other.yMax - other.yMin) * (other.xMax - other.xMin) - inter
        if (union <= 0) return 0f
        return inter.toFloat() / union
    }
}

/** Région d'obstacle ou de drop-off : géométrie, distance médiane, risque 0–3. */
data class ObstacleRegion(
    val bbox: ObstacleBox,
    val centroidY: Float,
    val centroidX: Float,
    val distanceM: Float,
    val areaPixels: Int,
    val type: RegionType,
    val risk: Int,
)

/** Régions d'obstacles (`obstacle_detection.py:129-300`). */
object ObstacleRegions {

    /**
     * Régions triées proche→loin, plafonnées à [GroundConfig.maxObstacles] :
     * érosion → composantes avec split de profondeur → dilatation ∩ masque →
     * fusion des chevauchements.
     */
    fun detect(
        labels: IntArray,
        depthM: FloatArray,
        width: Int,
        height: Int,
        config: GroundConfig = GroundConfig(),
    ): List<ObstacleRegion> {
        if (labels.size != width * height || depthM.size != labels.size) return emptyList()
        val all = ArrayList<ObstacleRegion>()
        all.addAll(regionsOfType(SceneLabels.OBSTACLE, RegionType.OBSTACLE, labels, depthM, width, height, config))
        all.addAll(regionsOfType(SceneLabels.DROPOFF, RegionType.DROPOFF, labels, depthM, width, height, config))
        val sorted = mergeOverlaps(all, config).sortedBy { it.distanceM }
        if (sorted.size <= config.maxObstacles) return sorted
        return sorted.subList(0, config.maxObstacles)
    }

    private fun regionsOfType(
        labelValue: Int,
        type: RegionType,
        labels: IntArray,
        depthM: FloatArray,
        width: Int,
        height: Int,
        config: GroundConfig,
    ): List<ObstacleRegion> {
        val out = ArrayList<ObstacleRegion>()
        val original = BooleanArray(labels.size) { labels[it] == labelValue }
        if (countTrue(original) < config.minRegionPixels) return out
        var eroded = ConnectedComponents.erode(original, width, height)
        if (countTrue(eroded) < config.minRegionPixels) eroded = original.copyOf()
        val labeled = depthAwareLabel(eroded, depthM, width, height, config)
        for (id in 1..labeled.count) {
            if (labeled.areas[id] <= 0) continue
            val region = BooleanArray(labels.size) { labeled.labels[it] == id }
            val described = describeRegion(region, original, depthM, width, height, type, config)
            if (described != null) out.add(described)
        }
        return out
    }

    /**
     * Étiquetage avec split de profondeur (`_depth_aware_label`) : une région
     * dont l'éventail dépasse [GroundConfig.obstacleDepthSplitM] est découpée
     * en bandes ré-étiquetées séparément (un mur ne fusionne pas avec un meuble).
     */
    private fun depthAwareLabel(
        eroded: BooleanArray,
        depthM: FloatArray,
        width: Int,
        height: Int,
        config: GroundConfig,
    ): LabeledRegions {
        val base = ConnectedComponents.label(eroded, width, height)
        val result = IntArray(eroded.size)
        val areas = ArrayList<Int>()
        areas.add(0)
        var nextId = 0
        for (id in 1..base.count) {
            val pixels = IntArray(base.areas[id])
            var count = 0
            var minD = Float.POSITIVE_INFINITY
            var maxD = Float.NEGATIVE_INFINITY
            for (i in eroded.indices) {
                if (base.labels[i] != id) continue
                pixels[count++] = i
                if (depthM[i] < minD) minD = depthM[i]
                if (depthM[i] > maxD) maxD = depthM[i]
            }
            if (maxD - minD <= config.obstacleDepthSplitM) {
                nextId++
                for (k in 0 until count) result[pixels[k]] = nextId
                areas.add(count)
            } else {
                nextId = splitBands(result, areas, nextId, pixels, count, depthM, minD, maxD, width, height, config)
            }
        }
        return LabeledRegions(result, nextId, areas.toIntArray())
    }

    /** Découpe une région profonde en bandes de [GroundConfig.obstacleDepthSplitM]. */
    private fun splitBands(
        result: IntArray,
        areas: ArrayList<Int>,
        startId: Int,
        pixels: IntArray,
        count: Int,
        depthM: FloatArray,
        minD: Float,
        maxD: Float,
        width: Int,
        height: Int,
        config: GroundConfig,
    ): Int {
        var nextId = startId
        val span = maxD - minD
        val bins = maxOf(1, ceil(span / config.obstacleDepthSplitM).toInt())
        for (b in 0 until bins) {
            var eps = 0f
            if (b == bins - 1) eps = 1e-6f
            val lo = minD + span * b / bins
            val hi = minD + span * (b + 1) / bins + eps
            val band = BooleanArray(result.size)
            for (k in 0 until count) {
                val d = depthM[pixels[k]]
                if (d >= lo && d < hi) band[pixels[k]] = true
            }
            val sub = ConnectedComponents.label(band, width, height)
            for (sid in 1..sub.count) {
                nextId++
                for (i in result.indices) {
                    if (sub.labels[i] == sid) result[i] = nextId
                }
                areas.add(sub.areas[sid])
            }
        }
        return nextId
    }

    /** Dilatation ∩ masque d'origine, puis boîte/centroïde/médiane/risque. */
    private fun describeRegion(
        region: BooleanArray,
        original: BooleanArray,
        depthM: FloatArray,
        width: Int,
        height: Int,
        type: RegionType,
        config: GroundConfig,
    ): ObstacleRegion? {
        val dilated = ConnectedComponents.dilate(region, width, height)
        val depths = ArrayList<Float>()
        var yMin = height
        var yMax = -1
        var xMin = width
        var xMax = -1
        var sumY = 0L
        var sumX = 0L
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                if (!dilated[i] || !original[i]) continue
                depths.add(depthM[i])
                if (y < yMin) yMin = y
                if (y > yMax) yMax = y
                if (x < xMin) xMin = x
                if (x > xMax) xMax = x
                sumY += y
                sumX += x
            }
        }
        if (depths.size < config.minRegionPixels) return null
        val median = DepthFusion.quickselectMedian(depths.toFloatArray(), depths.size)
        return ObstacleRegion(
            ObstacleBox(yMin, yMax, xMin, xMax),
            sumY.toFloat() / depths.size,
            sumX.toFloat() / depths.size,
            median,
            depths.size,
            type,
            riskFor(median, config),
        )
    }

    /** Fusion gloutonne : les grandes régions absorbent les chevauchantes proches. */
    private fun mergeOverlaps(regions: List<ObstacleRegion>, config: GroundConfig): List<ObstacleRegion> {
        if (regions.size <= 1) return regions
        val ordered = regions.sortedByDescending { it.areaPixels }
        val used = BooleanArray(ordered.size)
        val merged = ArrayList<ObstacleRegion>()
        for (i in ordered.indices) {
            if (used[i]) continue
            val group = ArrayList<ObstacleRegion>()
            group.add(ordered[i])
            used[i] = true
            for (j in i + 1 until ordered.size) {
                if (used[j]) continue
                val iou = ordered[i].bbox.iou(ordered[j].bbox)
                val gap = abs(ordered[i].distanceM - ordered[j].distanceM)
                if (iou > config.obstacleMergeIou && gap < config.obstacleMergeDepthM) {
                    group.add(ordered[j])
                    used[j] = true
                }
            }
            merged.add(mergedGroup(group, config))
        }
        return merged
    }

    /** Groupe fusionné : boîte union, distance la plus proche, centroïde pondéré. */
    private fun mergedGroup(group: List<ObstacleRegion>, config: GroundConfig): ObstacleRegion {
        var yMin = Int.MAX_VALUE
        var yMax = Int.MIN_VALUE
        var xMin = Int.MAX_VALUE
        var xMax = Int.MIN_VALUE
        var total = 0
        var closest = Float.POSITIVE_INFINITY
        var sumY = 0.0
        var sumX = 0.0
        for (region in group) {
            if (region.bbox.yMin < yMin) yMin = region.bbox.yMin
            if (region.bbox.yMax > yMax) yMax = region.bbox.yMax
            if (region.bbox.xMin < xMin) xMin = region.bbox.xMin
            if (region.bbox.xMax > xMax) xMax = region.bbox.xMax
            total += region.areaPixels
            if (region.distanceM < closest) closest = region.distanceM
            sumY += region.centroidY * region.areaPixels
            sumX += region.centroidX * region.areaPixels
        }
        return ObstacleRegion(
            ObstacleBox(yMin, yMax, xMin, xMax),
            (sumY / total).toFloat(),
            (sumX / total).toFloat(),
            closest,
            total,
            group[0].type,
            riskFor(closest, config),
        )
    }

    /** Risque 0–3 depuis la distance (`compute_risk_map`, seuils de zones). */
    private fun riskFor(distance: Float, config: GroundConfig): Int {
        if (distance < config.zoneCriticalM) return RiskZones.CRITICAL
        if (distance < config.zoneWarningM) return RiskZones.WARNING
        if (distance < config.zoneSafeM) return RiskZones.AWARE
        return RiskZones.SAFE
    }
}
