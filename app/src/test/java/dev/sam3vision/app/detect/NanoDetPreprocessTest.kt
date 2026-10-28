package dev.sam3vision.app.detect

import org.junit.Assert.assertEquals
import org.junit.Test

class NanoDetPreprocessTest {

    @Test
    fun `resizeSquare rend un carre 320`() {
        val src = IntArray(640 * 480) { 0xFF808080.toInt() }
        val out = NanoDetPreprocess.resizeSquare(src, 640, 480)
        assertEquals(320 * 320, out.size)
    }

    @Test
    fun `normalizeBGR ecrit BGR 0-255 en CHW`() {
        val px = (0xFF shl 24) or (200 shl 16) or (100 shl 8) or 50
        val buf = NanoDetPreprocess.normalizeBGR(IntArray(320 * 320) { px })
        val plane = 320 * 320
        val mean = NanoDetPreprocess.MEAN_BGR
        val std = NanoDetPreprocess.STD_BGR
        // Formule inverse exacte : canal 0 = B = 50, 1 = G = 100, 2 = R = 200.
        assertEquals(50f, buf.get(0) * std[0] + mean[0], 1e-3f)
        assertEquals(100f, buf.get(plane) * std[1] + mean[1], 1e-3f)
        assertEquals(200f, buf.get(2 * plane) * std[2] + mean[2], 1e-3f)
    }
}
