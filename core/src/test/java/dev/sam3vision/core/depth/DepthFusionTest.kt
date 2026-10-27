package dev.sam3vision.core.depth

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthFusionTest {

    private fun maskOf(vararg levels: Int) =
        GrayMask(2, 2, ByteArray(levels.size) { levels[it].toByte() })

    @Test
    fun `maskedStats ignore le fond et les bords sous le seuil`() {
        val mask = maskOf(255, 0, 128, 127)
        val depth = DepthMap(2, 2, floatArrayOf(10f, 999f, 20f, 888f))
        val stats = DepthFusion.maskedStats(mask, depth)!!

        assertEquals(2, stats.count)
        assertEquals(10f, stats.median, 0f)
        assertEquals(15f, stats.mean, 1e-6f)
        assertEquals(10f, stats.min, 0f)
        assertEquals(20f, stats.max, 0f)
    }

    @Test
    fun `maskedStats realigne la carte sur le masque`() {
        val mask = GrayMask(4, 4, ByteArray(16) { 255.toByte() })
        val depth = DepthMap(2, 2, floatArrayOf(4f, 4f, 4f, 4f))
        val stats = DepthFusion.maskedStats(mask, depth)!!

        assertEquals(16, stats.count)
        assertEquals(4f, stats.median, 1e-5f)
    }

    @Test
    fun `maskedStats rend null sur masque vide ou sans objet`() {
        val depth = DepthMap(2, 2, floatArrayOf(1f, 2f, 3f, 4f))
        assertNull(DepthFusion.maskedStats(maskOf(0, 0, 0, 0), depth))
        assertNull(DepthFusion.maskedStats(maskOf(127, 1, 64, 100), depth))
    }

    @Test
    fun `quickselectMedian egale la mediane triee`() {
        val random = java.util.Random(42)
        repeat(200) {
            val n = 1 + random.nextInt(200)
            val values = FloatArray(n) { random.nextFloat() * 100f }
            assertMedian(values)
        }
    }

    @Test
    fun `quickselectMedian tient les doublons et les tableaux ranges`() {
        val random = java.util.Random(7)
        repeat(100) {
            val n = 1 + random.nextInt(200)
            assertMedian(FloatArray(n) { random.nextInt(4).toFloat() })
        }
        assertMedian(FloatArray(101) { it.toFloat() })
        assertMedian(FloatArray(101) { (100 - it).toFloat() })
        assertMedian(FloatArray(64) { 3f })
        assertMedian(floatArrayOf(2f, 1f))
        assertMedian(floatArrayOf(1f))
    }

    private fun assertMedian(values: FloatArray) {
        val sorted = values.sorted()
        val expected = sorted[(values.size - 1) / 2]
        assertEquals(expected, DepthFusion.quickselectMedian(values.copyOf(), values.size), 0f)
    }

    @Test
    fun `nearerFirst trie proche devant loin`() {
        val near = MaskDepthStats(10, 0.9f, 0.9f, 0.8f, 1f)
        val far = MaskDepthStats(10, 0.2f, 0.2f, 0.1f, 0.3f)
        assertTrue(DepthFusion.nearerFirst(near, far) < 0)
        assertTrue(DepthFusion.nearerFirst(far, near) > 0)
        assertEquals(0, DepthFusion.nearerFirst(near, near))
    }

    @Test
    fun `occludes exige une marge franche`() {
        val front = MaskDepthStats(10, 1f, 1f, 1f, 1f)
        val back = MaskDepthStats(10, 0.5f, 0.5f, 0.5f, 0.5f)
        val close = MaskDepthStats(10, 0.95f, 0.95f, 0.95f, 0.95f)
        assertTrue(DepthFusion.occludes(front, back))
        assertFalse(DepthFusion.occludes(back, front))
        assertFalse(DepthFusion.occludes(front, close))
        assertFalse(DepthFusion.occludes(back, back))
    }

    @Test
    fun `calibrateScale et closenessToMeters reproduisent l ancre`() {
        val scale = DepthFusion.calibrateScale(anchorMeters = 2f, anchorCloseness = 0.5f)!!
        assertEquals(1f, scale, 1e-6f)
        assertEquals(2f, DepthFusion.closenessToMeters(0.5f, scale)!!, 1e-6f)
        assertEquals(1f, DepthFusion.closenessToMeters(1f, scale)!!, 1e-6f)
    }

    @Test
    fun `calibration nulle sur ancre inexploitable`() {
        assertNull(DepthFusion.calibrateScale(0f, 1f))
        assertNull(DepthFusion.calibrateScale(2f, 0f))
        assertNull(DepthFusion.calibrateScale(-1f, 1f))
        assertNull(DepthFusion.closenessToMeters(0f, 1f))
        assertNull(DepthFusion.closenessToMeters(1f, 0f))
    }
}
