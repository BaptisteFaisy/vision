package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Recentrage lampe du crop traversée : le centroïde des pixels saturés
 * ([CrossingPixels.lampCentroid], pur, seuils `:core`). Le recadrage Bitmap
 * ([CrossingPixels.recenteredBox]) n'est pas rejouable en JVM (comme
 * `CrossingPixels.crop`), seul le centroïde l'est.
 */
class CrossingRecenterTest {

    @Test
    fun `un pixel unique donne sa position`() {
        val pixels = IntArray(20 * 20) { BLACK }
        pixels[13 * 20 + 7] = GREEN
        val centroid = CrossingPixels.lampCentroid(pixels, 20, 20)
        assertEquals(7f, centroid?.first)
        assertEquals(13f, centroid?.second)
    }

    @Test
    fun `un disque centre donne le centre`() {
        val pixels = IntArray(20 * 20) { BLACK }
        paintDisc(pixels, 20, 20, 10, 10, 5, GREEN)
        val centroid = CrossingPixels.lampCentroid(pixels, 20, 20)
        assertEquals(10f, centroid?.first)
        assertEquals(10f, centroid?.second)
    }

    @Test
    fun `une barre basse excentree donne son centre`() {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 14 until 18) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        val centroid = CrossingPixels.lampCentroid(pixels, 20, 20)
        assertEquals(9.5f, centroid?.first)
        assertEquals(15.5f, centroid?.second)
    }

    @Test
    fun `le rouge franc compte aussi`() {
        val pixels = IntArray(20 * 20) { BLACK }
        pixels[5 * 20 + 3] = RED
        val centroid = CrossingPixels.lampCentroid(pixels, 20, 20)
        assertEquals(3f, centroid?.first)
        assertEquals(5f, centroid?.second)
    }

    @Test
    fun `le noir et le gris ne cadrent rien`() {
        assertNull(CrossingPixels.lampCentroid(IntArray(100) { BLACK }, 10, 10))
        assertNull(CrossingPixels.lampCentroid(IntArray(100) { GRAY }, 10, 10))
    }

    @Test
    fun `le vert eteint ne cadre rien`() {
        val pixels = IntArray(20 * 20) { BLACK }
        paintDisc(pixels, 20, 20, 10, 10, 5, dim(GREEN, 0.25f))
        assertNull(CrossingPixels.lampCentroid(pixels, 20, 20))
    }

    @Test
    fun `dimensions incoherentes - null`() {
        assertNull(CrossingPixels.lampCentroid(IntArray(10), 10, 10))
        assertNull(CrossingPixels.lampCentroid(IntArray(0), 0, 0))
        assertNull(CrossingPixels.lampCentroid(IntArray(100) { GREEN }, -1, 10))
    }

    /** Disque couleur dans un cadre noir. */
    private fun paintDisc(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, color: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) pixels[y * w + x] = color
            }
        }
    }

    /** Assombrit un pixel (facteur < 1). */
    private fun dim(pixel: Int, factor: Float): Int {
        val r = (((pixel shr 16) and 0xFF) * factor).toInt()
        val g = (((pixel shr 8) and 0xFF) * factor).toInt()
        val b = ((pixel and 0xFF) * factor).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    companion object {
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
    }
}
