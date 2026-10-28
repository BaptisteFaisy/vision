package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundConfigTest {

    @Test
    fun `defauts RANSAC repris de config-py`() {
        val config = GroundConfig()

        assertEquals(1000, config.ransacIterations)
        assertEquals(0.04f, config.ransacInlierM, 0f)
        assertEquals(25f, config.ransacMaxTiltDeg, 0f)
        assertEquals(0.40f, config.ransacEarlyStopRatio, 0f)
        assertEquals(0.15f, config.ransacMinInlierRatio, 0f)
        assertTrue(config.ransacWeightedSampling)
        assertEquals(5000, config.ransacMaxPoints)
    }

    @Test
    fun `defauts hauteurs et zones repris de config-py`() {
        val config = GroundConfig()

        assertEquals(0.3f, config.minDepthM, 0f)
        assertEquals(8.0f, config.maxDepthM, 0f)
        assertEquals(0.15f, config.maxGroundHeightM, 0f)
        assertEquals(0.10f, config.minObstacleHeightM, 0f)
        assertEquals(2.0f, config.maxObstacleHeightM, 0f)
        assertEquals(0.20f, config.dropThresholdM, 0f)
        assertEquals(1.0f, config.zoneCriticalM, 0f)
        assertEquals(2.5f, config.zoneWarningM, 0f)
        assertEquals(5.0f, config.zoneSafeM, 0f)
    }

    @Test
    fun `defauts gradient regions et seuils repris de config-py`() {
        val config = GroundConfig()

        assertEquals(0.03f, config.gradObstacleThreshold, 0f)
        assertEquals(5, config.gradSmoothKernel)
        assertEquals(1.0f, config.obstacleDepthSplitM, 0f)
        assertEquals(0.3f, config.obstacleMergeIou, 0f)
        assertEquals(10, config.maxObstacles)
        assertEquals(500, config.minRegionPixels)
        assertEquals(500, config.minSeeds)
        assertEquals(5f, config.pathCriticalPct, 0f)
        assertEquals(10f, config.pathWarningPct, 0f)
    }

    @Test
    fun `usable refuse les seuils desordonnes`() {
        assertTrue(GroundConfig().usable)
        assertFalse(GroundConfig().copy(minDepthM = 9f).usable)
        assertFalse(GroundConfig().copy(zoneWarningM = 0.5f).usable)
        assertFalse(GroundConfig().copy(ransacIterations = 0).usable)
    }
}
