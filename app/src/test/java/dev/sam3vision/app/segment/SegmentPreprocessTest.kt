package dev.sam3vision.app.segment

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentPreprocessTest {

    @Test
    fun `resizeLandscape rend du 640x360`() {
        val src = IntArray(640 * 480) { 0xFF808080.toInt() }
        val out = SegmentPreprocess.resizeLandscape(src, 640, 480)
        assertEquals(640 * 360, out.size)
    }

    @Test
    fun `normalizeNCHW ecrit RGB divise par 255 en NCHW`() {
        val px = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        val buf = SegmentPreprocess.normalizeNCHW(IntArray(640 * 360) { px })
        val plane = 640 * 360
        // Ordre des plans : R = 200, G = 100, B = 50.
        assertEquals(200f / 255f, buf.get(0), 1e-6f)
        assertEquals(100f / 255f, buf.get(plane), 1e-6f)
        assertEquals(50f / 255f, buf.get(2 * plane), 1e-6f)
    }
}
