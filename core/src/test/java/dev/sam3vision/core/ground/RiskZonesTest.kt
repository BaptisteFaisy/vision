package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskZonesTest {

    private val config = GroundConfig()

    @Test
    fun `compute cote obstacle et drop-off par zones ignore plafond`() {
        val labels = intArrayOf(
            SceneLabels.OBSTACLE, SceneLabels.OBSTACLE, SceneLabels.OBSTACLE, SceneLabels.OBSTACLE,
            SceneLabels.DROPOFF, SceneLabels.GROUND, SceneLabels.CEILING, SceneLabels.INVALID,
        )
        val depth = floatArrayOf(0.5f, 1.5f, 3f, 6f, 0.8f, 0.5f, 0.5f, 0.5f)

        val risk = RiskZones.compute(depth, labels, 8, 1, config)

        val expected = intArrayOf(
            RiskZones.CRITICAL, RiskZones.WARNING, RiskZones.AWARE, RiskZones.SAFE,
            RiskZones.CRITICAL, RiskZones.SAFE, RiskZones.SAFE, RiskZones.SAFE,
        )
        for (i in expected.indices) {
            assertEquals("pixel $i", expected[i], risk[i])
        }
    }

    @Test
    fun `pathSummary seuils critique alerte vigilance`() {
        val w = 30
        val h = 30
        val depth = FloatArray(w * h) { 4f }

        val clear = RiskZones.pathSummary(IntArray(w * h), depth, w, h, config)
        assertEquals(PathRisk.CLEAR, clear.overall)
        assertTrue(clear.pathClear)
        assertEquals(Float.POSITIVE_INFINITY, clear.closestHazardM, 0f)

        val critical = IntArray(w * h)
        for (y in h / 3 until h / 3 + 5) {
            for (x in w / 3 until 2 * w / 3) {
                critical[y * w + x] = RiskZones.CRITICAL
            }
        }
        val criticalSummary = RiskZones.pathSummary(critical, depth, w, h, config)
        assertEquals(PathRisk.CRITICAL, criticalSummary.overall)
        assertFalse(criticalSummary.pathClear)

        val warning = IntArray(w * h)
        for (y in h / 3 until h / 3 + 8) {
            for (x in w / 3 until 2 * w / 3) {
                warning[y * w + x] = RiskZones.WARNING
            }
        }
        assertEquals(PathRisk.WARNING, RiskZones.pathSummary(warning, depth, w, h, config).overall)

        val caution = IntArray(w * h)
        caution[(h / 2) * w + w / 2] = RiskZones.AWARE
        val cautionSummary = RiskZones.pathSummary(caution, depth, w, h, config)
        assertEquals(PathRisk.CAUTION, cautionSummary.overall)
        assertEquals(4f, cautionSummary.closestHazardM, 0f)
    }

    @Test
    fun `pathSummary incoherent rend degage`() {
        val summary = RiskZones.pathSummary(IntArray(3), FloatArray(3), 4, 4, config)

        assertEquals(PathRisk.CLEAR, summary.overall)
    }
}
