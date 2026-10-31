package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectionalFeedbackTest {

    private fun region(centroidX: Float, risk: Int, distance: Float): ObstacleRegion =
        ObstacleRegion(ObstacleBox(0, 1, 0, 1), 0f, centroidX, distance, 600, RegionType.OBSTACLE, risk)

    private fun clearSummary(): PathSummary =
        PathSummary(PathRisk.CLEAR, Float.POSITIVE_INFINITY, 0f, 0f)

    @Test
    fun `describe sans obstacle annonce chemin degage`() {
        val report = DirectionalFeedback.describe(emptyList(), clearSummary(), 90)

        assertEquals(0, report.left.risk)
        assertEquals(0, report.center.risk)
        assertEquals(0, report.right.risk)
        assertEquals(0f, report.left.intensity, 0f)
        assertEquals("Path clear. You can move forward.", report.message)
    }

    @Test
    fun `describe repartit en tiers et conseille la droite`() {
        val obstacles = listOf(
            region(10f, RiskZones.CRITICAL, 0.8f),
            region(45f, RiskZones.WARNING, 1.5f),
        )
        val summary = PathSummary(PathRisk.CRITICAL, 0.8f, 25f, 0f)

        val report = DirectionalFeedback.describe(obstacles, summary, 90)

        assertEquals(RiskZones.CRITICAL, report.left.risk)
        assertEquals(RiskZones.WARNING, report.center.risk)
        assertEquals(RiskZones.SAFE, report.right.risk)
        assertEquals(0.8f, report.left.closestM, 0f)
        assertEquals(1.0f, report.left.intensity, 0f)
        assertEquals(0.6f, report.center.intensity, 0f)
        assertEquals(
            "Stop, obstacle very close at 0.8 meters! Danger: left, center. " +
                "Safest direction: right.",
            report.message,
        )
    }

    @Test
    fun `describe garde le risque max et la distance min par tiers`() {
        val obstacles = listOf(
            region(80f, RiskZones.AWARE, 4f),
            region(85f, RiskZones.CRITICAL, 3f),
        )

        val report = DirectionalFeedback.describe(obstacles, clearSummary(), 90)

        assertEquals(RiskZones.CRITICAL, report.right.risk)
        assertEquals(3f, report.right.closestM, 0f)
    }

    @Test
    fun `describe frontieres de tiers au centre et a droite`() {
        val obstacles = listOf(region(30f, RiskZones.AWARE, 3f), region(60f, RiskZones.AWARE, 3f))

        val report = DirectionalFeedback.describe(obstacles, clearSummary(), 90)

        assertEquals(RiskZones.SAFE, report.left.risk)
        assertEquals(RiskZones.AWARE, report.center.risk)
        assertEquals(RiskZones.AWARE, report.right.risk)
    }

    @Test
    fun `describe sans ecart ne conseille aucune direction`() {
        val obstacles = listOf(
            region(10f, RiskZones.WARNING, 2f),
            region(45f, RiskZones.WARNING, 2f),
            region(80f, RiskZones.WARNING, 2f),
        )
        val summary = PathSummary(PathRisk.WARNING, 2f, 0f, 20f)

        val report = DirectionalFeedback.describe(obstacles, summary, 90)

        assertTrue(report.message.startsWith("Caution, obstacle ahead at 2.0 meters."))
        assertFalse(report.message.contains("Safest"))
    }
}
