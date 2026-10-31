package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

class PedLightColorTest {

    @Test
    fun `un cadre tout rouge est rouge`() {
        assertEquals(CrossingState.Red, PedLightColor.classify(redFrame(10, 10), 10, 10))
    }

    @Test
    fun `un cadre tout vert est vert`() {
        assertEquals(CrossingState.Green, PedLightColor.classify(greenFrame(10, 10), 10, 10))
    }

    @Test
    fun `un bonhomme rouge sur fond noir est rouge`() {
        val pixels = IntArray(20 * 20) { BLACK }
        paintDisc(pixels, 20, 20, RED)
        assertEquals(CrossingState.Red, PedLightColor.classify(pixels, 20, 20))
    }

    @Test
    fun `un bonhomme vert sur fond noir est vert`() {
        val pixels = IntArray(20 * 20) { BLACK }
        paintDisc(pixels, 20, 20, GREEN)
        assertEquals(CrossingState.Green, PedLightColor.classify(pixels, 20, 20))
    }

    @Test
    fun `un cadre gris attend rouge, binaire`() {
        assertEquals(CrossingState.Red, PedLightColor.classify(grayFrame(10, 10), 10, 10))
    }

    @Test
    fun `un cadre noir attend rouge, binaire`() {
        assertEquals(CrossingState.Red, PedLightColor.classify(IntArray(100) { BLACK }, 10, 10))
    }

    @Test
    fun `un match nul rouge vert attend rouge, binaire`() {
        val pixels = IntArray(100) { index -> if (index % 2 == 0) RED else GREEN }
        assertEquals(CrossingState.Red, PedLightColor.classify(pixels, 10, 10))
    }

    @Test
    fun `quelques pixels rouges perdus attendent rouge, binaire`() {
        val pixels = IntArray(100) { BLACK }
        pixels[0] = RED
        pixels[1] = RED
        assertEquals(CrossingState.Red, PedLightColor.classify(pixels, 10, 10))
    }

    @Test
    fun `un tableau incoherent attend rouge, binaire`() {
        assertEquals(CrossingState.Red, PedLightColor.classify(IntArray(0), 0, 0))
        assertEquals(CrossingState.Red, PedLightColor.classify(IntArray(10), 10, 10))
        assertEquals(CrossingState.Red, PedLightColor.classify(IntArray(100), -1, 10))
    }

    private fun redFrame(w: Int, h: Int) = IntArray(w * h) { RED }

    private fun greenFrame(w: Int, h: Int) = IntArray(w * h) { GREEN }

    private fun grayFrame(w: Int, h: Int) = IntArray(w * h) { GRAY }

    /** Disque coloré au centre (≈ 28 % du cadre) : un bonhomme simplifié. */
    private fun paintDisc(pixels: IntArray, w: Int, h: Int, color: Int) {
        val radius = w / 3.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - w / 2.0
                val dy = y - h / 2.0
                if (dx * dx + dy * dy <= radius * radius) pixels[y * w + x] = color
            }
        }
    }

    companion object {
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
    }
}
