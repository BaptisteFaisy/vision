package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte anti-confusion du vert piéton : pixels synthétiques (frontières des
 * seuils) + échantillon réel `feuxfr/` (9 crops Panoramax ≤ 48 px, voir
 * ATTRIBUTION.md). Les seuils sont calibrés sur feux français (10/2026) :
 * ces tests verrouillent 0 vert sur rouge et le rappel bonhomme.
 */
class PedLightGateTest {

    @Test
    fun `barre verte basse elancee - acceptee`() {
        assertTrue(PedLightGate.acceptGreen(barBottom(), 20, 20))
    }

    @Test
    fun `disque vert centre - refuse (feu rond, pas bonhomme)`() {
        assertFalse(PedLightGate.acceptGreen(disc(10, 10, 5), 20, 20))
    }

    @Test
    fun `disque vert bas - refuse malgre la position`() {
        assertFalse(PedLightGate.acceptGreen(disc(10, 14, 5), 20, 20))
    }

    @Test
    fun `barre verte haute - refusee (vert confine en haut)`() {
        assertFalse(PedLightGate.acceptGreen(barTop(), 20, 20))
    }

    @Test
    fun `vert melange de rouge franc - refuse`() {
        val pixels = barBottom()
        paintDisc(pixels, 20, 20, 10, 5, 3, RED)
        assertFalse(PedLightGate.acceptGreen(pixels, 20, 20))
    }

    @Test
    fun `cadres vides ou incoherents - refuses`() {
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { BLACK }, 10, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(100) { GRAY }, 10, 10))
        assertFalse(PedLightGate.acceptGreen(IntArray(0), 0, 0))
        assertFalse(PedLightGate.acceptGreen(IntArray(10), 10, 10))
    }

    @Test
    fun `reflet vert minuscule - refuse`() {
        val pixels = IntArray(400) { BLACK }
        pixels[15 * 20 + 10] = GREEN
        pixels[15 * 20 + 11] = GREEN
        assertFalse(PedLightGate.acceptGreen(pixels, 20, 20))
    }

    @Test
    fun `barre decalee a gauche ou droite - acceptee (translation)`() {
        assertTrue(PedLightGate.acceptGreen(barBottom(x0 = 2, x1 = 5), 20, 20))
        assertTrue(PedLightGate.acceptGreen(barBottom(x0 = 14, x1 = 17), 20, 20))
    }

    @Test
    fun `barre sous-exposee - acceptee puis doute, jamais d invention`() {
        assertTrue(PedLightGate.acceptGreen(dim(barBottom(), 0.5f), 20, 20))
        assertFalse(PedLightGate.acceptGreen(dim(barBottom(), 0.25f), 20, 20))
    }

    @Test
    fun `verdict - cadre tout vert doute (pas elance, pas bonhomme)`() {
        assertEquals(
            CrossingState.Unknown,
            PedLightGate.verdict(IntArray(100) { CYAN_GREEN }, 10, 10),
        )
    }

    @Test
    fun `verdict - le rouge ne devient jamais vert`() {
        assertNotEquals(
            CrossingState.Green,
            PedLightGate.verdict(disc(10, 10, 5, RED), 20, 20),
        )
    }

    @Test
    fun `verdict - noir et gris restent un doute`() {
        assertEquals(CrossingState.Unknown, PedLightGate.verdict(IntArray(100) { BLACK }, 10, 10))
        assertEquals(CrossingState.Unknown, PedLightGate.verdict(IntArray(100) { GRAY }, 10, 10))
    }

    @Test
    fun `seuil fraction - 3px elances passent, 1px refuse`() {
        assertTrue(PedLightGate.acceptGreen(bar(1, 3), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(1, 2), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(1, 1), 20, 20))
    }

    @Test
    fun `seuil elancement - 1,67 accepte, 1,43 refuse`() {
        assertTrue(PedLightGate.acceptGreen(bar(6, 10), 20, 20))
        assertFalse(PedLightGate.acceptGreen(bar(7, 10), 20, 20))
    }

    @Test
    fun `seuil bas - 50 pourcent accepte, 20 pourcent refuse`() {
        assertTrue(PedLightGate.acceptGreen(barAt(5), 20, 20))
        assertFalse(PedLightGate.acceptGreen(barAt(2), 20, 20))
    }

    @Test
    fun `feux fr - rouges sombres jamais verts`() {
        assertNotEquals(CrossingState.Green, verdictOf("fr_red_dim_138.ppm"))
        assertNotEquals(CrossingState.Green, verdictOf("fr_red_noise_732.ppm"))
    }

    @Test
    fun `feux fr - bonhomme rouge reste rouge`() {
        assertEquals(CrossingState.Red, verdictOf("fr_red_fig_548.ppm"))
    }

    @Test
    fun `feux fr - bonhommes verts acceptes`() {
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_21.ppm"))
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_302.ppm"))
        assertEquals(CrossingState.Green, verdictOf("fr_green_fig_243.ppm"))
    }

    @Test
    fun `feux fr - disques voiture refuses`() {
        assertNotEquals(CrossingState.Green, verdictOf("fr_cardisc_close_183.ppm"))
        assertNotEquals(CrossingState.Green, verdictOf("fr_cardisc_367.ppm"))
    }

    @Test
    fun `feux fr - pose large doute documente`() {
        assertNotEquals(CrossingState.Green, verdictOf("fr_green_widepose_355.ppm"))
    }

    /** Barre verte verticale dans la moitié basse (bonhomme simplifié). */
    private fun barBottom(x0: Int = 8, x1: Int = 11): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 10 until 20) {
            for (x in x0..x1) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Même barre dans la moitié haute (mauvaise position). */
    private fun barTop(): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 0 until 10) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Barre `w` x `h` calée en bas (fraction et élancement réglables). */
    private fun bar(w: Int, h: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in 20 - h until 20) {
            for (x in 10 - w / 2 until 10 - w / 2 + w) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Barre 4 x 10 placée en [y0] (part basse réglable). */
    private fun barAt(y0: Int): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        for (y in y0 until y0 + 10) {
            for (x in 8..11) pixels[y * 20 + x] = GREEN
        }
        return pixels
    }

    /** Verdict sur un crop réel `feuxfr/` (PPM ≤ 48 px, voir ATTRIBUTION.md). */
    private fun verdictOf(name: String): CrossingState {
        val bytes = javaClass.classLoader.getResourceAsStream("feuxfr/$name")?.readBytes()
            ?: error("ressource manquante : feuxfr/$name")
        val (pixels, width, height) = ppmPixels(bytes)
        return PedLightGate.verdict(pixels, width, height)
    }

    /** Pixels ARGB d'un PPM P6 brut (pas d'ImageIO sur le classpath des tests). */
    private fun ppmPixels(bytes: ByteArray): Triple<IntArray, Int, Int> {
        var pos = 0
        fun token(): String {
            while (pos < bytes.size && bytes[pos] in SPACES) pos++
            val start = pos
            while (pos < bytes.size && bytes[pos] !in SPACES) pos++
            return String(bytes, start, pos - start, Charsets.US_ASCII)
        }
        require(token() == "P6") { "PPM attendu" }
        val width = token().toInt()
        val height = token().toInt()
        require(token() == "255") { "PPM 255 attendu" }
        pos++
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val r = bytes[pos++].toInt() and 0xFF
            val g = bytes[pos++].toInt() and 0xFF
            val b = bytes[pos++].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Triple(pixels, width, height)
    }

    /** Disque de couleur sur fond noir. */
    private fun disc(cx: Int, cy: Int, radius: Int, color: Int = GREEN): IntArray {
        val pixels = IntArray(20 * 20) { BLACK }
        paintDisc(pixels, 20, 20, cx, cy, radius, color)
        return pixels
    }

    private fun paintDisc(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, color: Int) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                if (dx * dx + dy * dy <= radius * radius) pixels[y * w + x] = color
            }
        }
    }

    /** Sous-exposition : canaux multipliés, teinte et saturation conservées. */
    private fun dim(pixels: IntArray, factor: Float): IntArray {
        return IntArray(pixels.size) { index ->
            val pixel = pixels[index]
            if (pixel == BLACK) return@IntArray BLACK
            val r = (((pixel shr 16) and 0xFF) * factor).toInt()
            val g = (((pixel shr 8) and 0xFF) * factor).toInt()
            val b = ((pixel and 0xFF) * factor).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    companion object {
        private const val RED = 0xFFFF0000.toInt()
        private const val GREEN = 0xFF00FF00.toInt()
        private const val CYAN_GREEN = 0xFF00FFAA.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val GRAY = 0xFF808080.toInt()
        private val SPACES = byteArrayOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte())
    }
}
