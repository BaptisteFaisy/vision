package dev.sam3vision.app.depth

import org.junit.Assert.assertEquals
import org.junit.Test

class DepthPreprocessTest {

    @Test
    fun `resize rend un carre de la taille demandee`() {
        val src = IntArray(8 * 4) { 0xFF808080.toInt() }
        val out = DepthPreprocess.resizeArgbBilinear(src, 8, 4, 256)
        assertEquals(256 * 256, out.size)
    }

    @Test
    fun `resize conserve une image uniforme`() {
        val src = IntArray(6 * 6) { 0xFF102030.toInt() }
        val out = DepthPreprocess.resizeArgbBilinear(src, 6, 6, 12)
        out.forEach { assertEquals(0xFF102030.toInt(), it) }
    }

    @Test
    fun `resize carre identique recopie`() {
        val src = IntArray(4 * 4) { it }
        val out = DepthPreprocess.resizeArgbBilinear(src, 4, 4, 4)
        assertEquals(src.toList(), out.toList())
    }

    @Test
    fun `resize rect rend un rectangle de la taille demandee`() {
        val src = IntArray(8 * 4) { 0xFF808080.toInt() }
        val out = DepthPreprocess.resizeArgbBilinearRect(src, 8, 4, 640, 360)
        assertEquals(640 * 360, out.size)
    }

    @Test
    fun `resize rect conserve une image uniforme`() {
        val src = IntArray(6 * 6) { 0xFF102030.toInt() }
        val out = DepthPreprocess.resizeArgbBilinearRect(src, 6, 6, 12, 6)
        out.forEach { assertEquals(0xFF102030.toInt(), it) }
    }

    @Test
    fun `normalizeCHW centre le gris moyen pres de zero`() {
        val gray = 0xFF808080.toInt()
        val buf = DepthPreprocess.normalizeCHW(
            IntArray(2 * 2) { gray }, 2,
            floatArrayOf(0.485f, 0.456f, 0.406f),
            floatArrayOf(0.229f, 0.224f, 0.225f),
        )
        assertEquals(2 * 2 * 3, buf.remaining())
        val px = 0x80 / 255f
        assertEquals((px - 0.485f) / 0.229f, buf.get(0), 1e-6f)
        assertEquals((px - 0.456f) / 0.224f, buf.get(4), 1e-6f)
        assertEquals((px - 0.406f) / 0.225f, buf.get(8), 1e-6f)
    }
}
