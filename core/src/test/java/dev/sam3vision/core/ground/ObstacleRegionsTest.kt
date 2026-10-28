package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleRegionsTest {

    private val config = GroundConfig()

    private fun stamp(
        labels: IntArray,
        depth: FloatArray,
        w: Int,
        value: Int,
        meters: Float,
        y0: Int,
        y1: Int,
        x0: Int,
        x1: Int,
    ) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                labels[y * w + x] = value
                depth[y * w + x] = meters
            }
        }
    }

    @Test
    fun `detect retrouve boite bbox distance et risque`() {
        val w = 64
        val h = 48
        val labels = IntArray(w * h) { SceneLabels.GROUND }
        val depth = FloatArray(w * h) { 5f }
        stamp(labels, depth, w, SceneLabels.OBSTACLE, 2f, 10, 34, 10, 39)

        val regions = ObstacleRegions.detect(labels, depth, w, h, config)

        assertEquals(1, regions.size)
        val region = regions[0]
        assertEquals(ObstacleBox(10, 34, 10, 39), region.bbox)
        assertEquals(22f, region.centroidY, 1e-6f)
        assertEquals(24.5f, region.centroidX, 1e-6f)
        assertEquals(2f, region.distanceM, 0f)
        assertEquals(750, region.areaPixels)
        assertEquals(RegionType.OBSTACLE, region.type)
        assertEquals(RiskZones.WARNING, region.risk)
    }

    @Test
    fun `detect marque le type drop-off et risque vigilance`() {
        val w = 64
        val h = 48
        val labels = IntArray(w * h) { SceneLabels.GROUND }
        val depth = FloatArray(w * h) { 5f }
        stamp(labels, depth, w, SceneLabels.DROPOFF, 4f, 10, 34, 10, 39)

        val regions = ObstacleRegions.detect(labels, depth, w, h, config)

        assertEquals(1, regions.size)
        assertEquals(RegionType.DROPOFF, regions[0].type)
        assertEquals(4f, regions[0].distanceM, 0f)
        assertEquals(RiskZones.AWARE, regions[0].risk)
    }

    @Test
    fun `detect scinde une region a cheval sur 1 m de profondeur`() {
        val w = 64
        val h = 48
        val labels = IntArray(w * h) { SceneLabels.GROUND }
        val depth = FloatArray(w * h) { 5f }
        stamp(labels, depth, w, SceneLabels.OBSTACLE, 1f, 4, 43, 12, 26)
        stamp(labels, depth, w, SceneLabels.OBSTACLE, 3f, 4, 43, 27, 41)

        val regions = ObstacleRegions.detect(labels, depth, w, h, config)

        assertEquals(2, regions.size)
        assertEquals(1f, regions[0].distanceM, 0f)
        assertEquals(3f, regions[1].distanceM, 0f)
    }

    @Test
    fun `detect fusionne cadre et emboite a meme profondeur`() {
        val w = 64
        val h = 48
        val labels = IntArray(w * h) { SceneLabels.GROUND }
        val depth = FloatArray(w * h) { 5f }
        stamp(labels, depth, w, SceneLabels.OBSTACLE, 1.5f, 2, 45, 8, 41)
        stamp(labels, depth, w, SceneLabels.DROPOFF, 1.5f, 12, 38, 14, 34)

        val regions = ObstacleRegions.detect(labels, depth, w, h, config)

        assertEquals(1, regions.size)
        assertEquals(RegionType.OBSTACLE, regions[0].type)
        assertEquals(1496, regions[0].areaPixels)
        assertEquals(1.5f, regions[0].distanceM, 0f)
        assertEquals(ObstacleBox(2, 45, 8, 41), regions[0].bbox)
    }

    @Test
    fun `detect trie proche-loin et plafonne a 10`() {
        val w = 240
        val h = 200
        val labels = IntArray(w * h) { SceneLabels.GROUND }
        val depth = FloatArray(w * h) { 7f }
        for (k in 0 until 12) {
            val ox = (k % 4) * 60 + 5
            val oy = (k / 4) * 60 + 5
            stamp(labels, depth, w, SceneLabels.OBSTACLE, 0.5f + k * 0.5f, oy, oy + 21, ox, ox + 31)
        }

        val regions = ObstacleRegions.detect(labels, depth, w, h, config)

        assertEquals(10, regions.size)
        assertEquals(0.5f, regions[0].distanceM, 0f)
        assertEquals(5f, regions[9].distanceM, 0f)
        for (k in 1 until regions.size) {
            assertTrue(regions[k].distanceM >= regions[k - 1].distanceM)
        }
    }

    @Test
    fun `detect vide sur tailles incoherentes`() {
        assertTrue(ObstacleRegions.detect(IntArray(3), FloatArray(3), 4, 4, config).isEmpty())
    }
}
