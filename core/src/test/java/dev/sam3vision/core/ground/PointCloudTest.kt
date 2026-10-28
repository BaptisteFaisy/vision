package dev.sam3vision.core.ground

import dev.sam3vision.core.depth.DepthMap
import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PointCloudTest {

    @Test
    fun `retroprojection exacte X droite Y-up`() {
        val w = SyntheticScene.W
        val h = SyntheticScene.H
        val cloud = SyntheticScene.cloudOf(FloatArray(w * h) { 2f }, w, h)
        val cx = (w - 1) / 2f
        val cy = (h - 1) / 2f

        val i = 3 * w + 11
        assertEquals((11 - cx) * 2f / SyntheticScene.FOCAL, cloud.xs[i], 1e-6f)
        assertEquals(-((3 - cy) * 2f / SyntheticScene.FOCAL), cloud.ys[i], 1e-6f)
        assertEquals(2f, cloud.zs[i], 0f)
    }

    @Test
    fun `bas de l-image sous la camera haut au-dessus`() {
        val w = SyntheticScene.W
        val h = SyntheticScene.H
        val cloud = SyntheticScene.cloudOf(FloatArray(w * h) { 2f }, w, h)

        assertTrue(cloud.ys[40 * w + 10] < 0f)
        assertTrue(cloud.ys[5 * w + 10] > 0f)
    }

    @Test
    fun `plage valide stricte 0-3 a 8 m`() {
        val depths = floatArrayOf(0.2f, 0.3f, 0.31f, 7.99f, 8f, 8.1f, Float.NaN, Float.POSITIVE_INFINITY)
        val cloud = PointClouds.fromMeters(depths, 8, 1, SyntheticScene.intrinsics(8, 1))

        val expected = booleanArrayOf(false, false, true, true, false, false, false, false)
        for (i in expected.indices) {
            assertEquals("pixel $i", expected[i], cloud.valid[i])
        }
    }

    @Test
    fun `fromCloseness applique echelle sur proximite`() {
        val depth = DepthMap(3, 1, floatArrayOf(4f, 0f, -2f))
        val cloud = PointClouds.fromCloseness(depth, 8f, SyntheticScene.intrinsics(3, 1))

        assertTrue(cloud.valid[0])
        assertEquals(2f, cloud.zs[0], 1e-6f)
        assertFalse(cloud.valid[1])
        assertFalse(cloud.valid[2])
    }

    @Test
    fun `fromLens derive focale et centre optique`() {
        val got = CameraIntrinsics.fromLens(LensSpec.Default, 64, 48)!!

        assertEquals(LensSpec.Default.focalPx(64, 48), got.fx, 0f)
        assertEquals(got.fx, got.fy, 0f)
        assertEquals(31.5f, got.cx, 0f)
        assertEquals(23.5f, got.cy, 0f)
        assertTrue(got.usable)
    }

    @Test
    fun `fromLens rend null sur calibrage inutilisable`() {
        assertNull(CameraIntrinsics.fromLens(LensSpec(1000f, 1000f), 64, 48))
    }

    @Test
    fun `taille incoherente rend un nuage invalide`() {
        val cloud = PointClouds.fromMeters(FloatArray(3) { 2f }, 8, 1, SyntheticScene.intrinsics(8, 1))

        for (i in 0 until cloud.size) {
            assertFalse(cloud.valid[i])
        }
    }
}
