package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class RansacPlaneTest {

    @Test
    fun `fit trouve le sol synthetique`() {
        val w = SyntheticScene.W
        val h = SyntheticScene.H
        val cloud = SyntheticScene.cloudOf(SyntheticScene.flatFloorDepth(), w, h)
        val all = BooleanArray(cloud.size) { true }
        val candidates = SyntheticScene.gather(cloud, all)

        val fit = RansacPlane.fit(candidates.xs, candidates.ys, candidates.zs, candidates.rows, 0f)

        assertNotNull(fit.plane)
        assertEquals(0f, fit.plane!!.a, 1e-3f)
        assertEquals(1f, fit.plane!!.b, 1e-3f)
        assertEquals(0f, fit.plane!!.c, 1e-3f)
        assertEquals(SyntheticScene.CAM_HEIGHT, fit.plane!!.d, 1e-3f)
        assertTrue(fit.highConfidence)
        assertTrue(fit.inlierRatio > 0.99f)
    }

    @Test
    fun `fit resiste a une boite outlier`() {
        val w = SyntheticScene.W
        val h = SyntheticScene.H
        val depth = SyntheticScene.withBox(SyntheticScene.flatFloorDepth(), w, h, 36, 44, 20, 44, 2f)
        val cloud = SyntheticScene.cloudOf(depth, w, h)
        val all = BooleanArray(cloud.size) { true }
        val candidates = SyntheticScene.gather(cloud, all)

        val fit = RansacPlane.fit(candidates.xs, candidates.ys, candidates.zs, candidates.rows, 0f)

        assertNotNull(fit.plane)
        assertEquals(1f, fit.plane!!.b, 1e-2f)
        assertEquals(SyntheticScene.CAM_HEIGHT, fit.plane!!.d, 0.05f)
        assertTrue(fit.highConfidence)
        assertTrue(fit.inlierRatio > 0.7f)
    }

    @Test
    fun `fit rejette un mur pur sans plan`() {
        val side = 20
        val n = side * side
        val xs = FloatArray(n) { 2f }
        val ys = FloatArray(n) { i -> -2f + (i / side) * 0.1f }
        val zs = FloatArray(n) { i -> 1f + (i % side) * 0.1f }
        val rows = IntArray(n) { it / side }

        val fit = RansacPlane.fit(xs, ys, zs, rows, 0f)

        assertNull(fit.plane)
        assertFalse(fit.highConfidence)
        assertEquals(0f, fit.inlierRatio, 0f)
    }

    @Test
    fun `fit redresse un pique de telephone via pitchRad`() {
        val pitch = 0.5
        val side = 12
        val n = side * side
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val zs = FloatArray(n)
        val rows = IntArray(n)
        val cosP = cos(pitch)
        val sinP = kotlin.math.sin(pitch)
        for (i in 0 until n) {
            val wx = -2f + (i % side) * (4f / (side - 1))
            val wz = 1f + (i / side) * (4f / (side - 1))
            xs[i] = wx
            ys[i] = (-SyntheticScene.CAM_HEIGHT * cosP + wz * sinP).toFloat()
            zs[i] = (SyntheticScene.CAM_HEIGHT * sinP + wz * cosP).toFloat()
            rows[i] = i / side
        }

        val tilted = RansacPlane.fit(xs, ys, zs, rows, pitch.toFloat())
        assertNotNull(tilted.plane)
        assertTrue(tilted.highConfidence)
        assertEquals(cos(pitch).toFloat(), tilted.plane!!.b, 1e-4f)

        val flat = RansacPlane.fit(xs, ys, zs, rows, 0f)
        assertNull(flat.plane)
    }

    @Test
    fun `fit degenere sous 3 points`() {
        val fit = RansacPlane.fit(floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(1f), intArrayOf(0), 0f)

        assertNull(fit.plane)
        assertEquals(0, fit.iterations)
    }

    @Test
    fun `subsampledIndices plafonne sans doublon et reproductible`() {
        val capped = RansacPlane.subsampledIndices(10_000, 5000, 42L)
        val again = RansacPlane.subsampledIndices(10_000, 5000, 42L)

        assertEquals(5000, capped.size)
        assertTrue(capped.toSet().size == 5000)
        for (i in capped.indices) {
            assertTrue(capped[i] in 0 until 10_000)
            assertEquals(again[i], capped[i])
        }
        val small = RansacPlane.subsampledIndices(100, 5000, 42L)
        assertEquals(100, small.size)
        for (i in 0 until 100) {
            assertEquals(i, small[i])
        }
    }
}
