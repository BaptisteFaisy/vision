package dev.sam3vision.app.glasses.bench

import dev.sam3vision.app.glasses.Yuv420Layout
import dev.sam3vision.app.glasses.YuvConverter
import dev.sam3vision.app.glasses.inferYuv420Layout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests JVM purs de la conversion YUV → ARGB : aucune classe Android n'est
 * touchée (pas de `android.graphics`, pas de Robolectric).
 */
class YuvConverterTest {

    /** 4x4 serré : 16 octets de Y, puis U et V de 2x2. */
    private fun tight() = Yuv420Layout(width = 4, height = 4, yRowStride = 4, uvRowStride = 2)

    /**
     * Construit un buffer YUV 4:2:0 dont **tous** les octets de padding valent
     * 0xFF : si la conversion lisait un octet de trop, le test le verrait.
     */
    private fun buildYuv(
        layout: Yuv420Layout,
        yAt: (row: Int, col: Int) -> Int = { _, _ -> 76 },
        u: Int = 128,
        v: Int = 128,
    ): ByteArray {
        val data = ByteArray(layout.requiredBytes()) { 0xFF.toByte() }
        for (row in 0 until layout.height) {
            for (col in 0 until layout.width) {
                data[row * layout.yRowStride + col] = yAt(row, col).toByte()
            }
        }
        for (row in 0 until layout.chromaHeight) {
            for (col in 0 until layout.chromaWidth) {
                val cell = row * layout.uvRowStride + col * layout.uvPixelStride
                data[layout.uOffset + cell] = u.toByte()
                data[layout.vOffset + cell] = v.toByte()
            }
        }
        return data
    }

    private fun convert(layout: Yuv420Layout, data: ByteArray = buildYuv(layout)): IntArray {
        val out = IntArray(layout.width * layout.height)
        assertTrue("la conversion doit réussir", YuvConverter.yuv420ToArgb(data, layout, out))
        return out
    }

    @Test
    fun `gris neutre donne des canaux egaux et un alpha opaque`() {
        val out = convert(tight())
        assertEquals(16, out.size)
        // Y = 76, U = V = 128 → gris BT.601, alpha 0xFF.
        out.forEach { assertEquals(0xFF4C4C4C.toInt(), it) }
    }

    @Test
    fun `noir et blanc restent noir et blanc`() {
        val black = convert(tight(), buildYuv(tight(), yAt = { _, _ -> 0 }))
        black.forEach { assertEquals(0xFF000000.toInt(), it) }

        val white = convert(tight(), buildYuv(tight(), yAt = { _, _ -> 255 }))
        white.forEach { assertEquals(0xFFFFFFFF.toInt(), it) }
    }

    @Test
    fun `une chroma rouge teinte les pixels sans deborder`() {
        // U = 90 (bleu faible), V = 240 (rouge fort) : dominante rouge attendue.
        val out = convert(tight(), buildYuv(tight(), yAt = { _, _ -> 76 }, u = 90, v = 240))
        out.forEach { pixel ->
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            assertTrue("rouge dominant (r=$r g=$g b=$b)", r > 150 && r > g && r > b)
            assertEquals(0xFF, (pixel ushr 24) and 0xFF)
        }
    }

    @Test
    fun `un rowStride plus grand que la largeur ignore le padding`() {
        val yAt: (Int, Int) -> Int = { row, col -> 10 * (row * 4 + col) }
        val tightPixels = convert(tight(), buildYuv(tight(), yAt = yAt))
        // Même image, lignes paddées à 8 octets et chroma à 4.
        val padded = Yuv420Layout(width = 4, height = 4, yRowStride = 8, uvRowStride = 4)
        val paddedPixels = convert(padded, buildYuv(padded, yAt = yAt))
        assertArrayEquals(tightPixels, paddedPixels)
    }

    @Test
    fun `le semi-planaire NV12 pixelStride 2 est lu comme l I420`() {
        val planar = convert(tight(), buildYuv(tight()))
        // UV entrelacés : 4 octets par ligne de chroma (U0 V0 U1 V1).
        val nv12 = Yuv420Layout(width = 4, height = 4, yRowStride = 4, uvRowStride = 4, uvPixelStride = 2)
        val nv12Pixels = convert(nv12, buildYuv(nv12))
        assertArrayEquals(planar, nv12Pixels)
    }

    @Test
    fun `les entrees incoherentes sont refusees sans lever`() {
        val out = IntArray(16)
        // Buffer vide.
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(0), tight(), out))
        // Dimensions nulles.
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(24), tight().copy(width = 0), out))
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(24), tight().copy(height = 0), out))
        // rowStride plus court que la largeur.
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(24), tight().copy(yRowStride = 2), out))
        // pixelStride absurde.
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(24), tight().copy(uvPixelStride = 0), out))
        // Tableau de sortie trop petit.
        assertFalse(YuvConverter.yuv420ToArgb(buildYuv(tight()), tight(), IntArray(4)))
        // Buffer tronqué d'un octet.
        assertFalse(YuvConverter.yuv420ToArgb(buildYuv(tight()).copyOf(23), tight(), out))
    }

    @Test
    fun `la disposition serree est deduite de la taille du buffer`() {
        assertEquals(tight(), inferYuv420Layout(byteCount = 24, width = 4, height = 4))
    }

    @Test
    fun `la disposition avec padding de ligne est deduite de la taille du buffer`() {
        // 8 * 4 (Y) + 2 * 4 * 2 (U et V) = 48 octets.
        val expected = Yuv420Layout(width = 4, height = 4, yRowStride = 8, uvRowStride = 4)
        assertEquals(expected, inferYuv420Layout(byteCount = 48, width = 4, height = 4))
    }

    @Test
    fun `une taille de buffer aberrante ne donne pas de disposition`() {
        assertNull(inferYuv420Layout(byteCount = 0, width = 4, height = 4))
        assertNull(inferYuv420Layout(byteCount = 23, width = 4, height = 4))
        assertNull(inferYuv420Layout(byteCount = 24, width = 0, height = 4))
    }

    @Test
    fun `des dimensions impaires arrondissent la chroma au pixel superieur`() {
        // 3x3 : chroma 2x2 (une ligne et une colonne de chroma en trop, normales en 4:2:0).
        val odd = Yuv420Layout(width = 3, height = 3, yRowStride = 3, uvRowStride = 2)
        assertEquals(2, odd.chromaWidth)
        assertEquals(2, odd.chromaHeight)
        assertEquals(17, odd.requiredBytes())
        val out = convert(odd)
        assertEquals("3x3 = 9 pixels", 9, out.size)
        // Les pixels de la dernière ligne et de la dernière colonne lisent bien
        // la cellule de chroma (1,1) et pas un octet de padding.
        out.forEach { assertEquals(0xFF4C4C4C.toInt(), it) }
    }

    @Test
    fun `les canaux restent bornes pour des chromas extremes`() {
        val extremes = listOf(
            Triple(0, 0, 0),
            Triple(255, 255, 255),
            Triple(0, 255, 0),
            Triple(255, 0, 255),
            Triple(128, 16, 240),
        )
        extremes.forEach { (y, u, v) ->
            val out = convert(tight(), buildYuv(tight(), yAt = { _, _ -> y }, u = u, v = v))
            out.forEach { pixel ->
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                assertTrue("canaux bornés (y=$y u=$u v=$v → $r/$g/$b)", r in 0..255 && g in 0..255 && b in 0..255)
                assertEquals("alpha opaque", 0xFF, (pixel ushr 24) and 0xFF)
            }
        }
    }

    /**
     * Limite mesurée de [inferYuv420Layout] : un buffer NV12 serré fait la même
     * taille qu'un I420 serré (24 octets en 4x4), donc l'inférence — qui ne voit
     * que la taille — renvoie une disposition **planaire**. Lue ainsi, une vraie
     * frame NV12 donne des couleurs fausses ; ce test verrouille le constat au
     * lieu de le laisser découvrir sur matériel.
     */
    @Test
    fun `un buffer NV12 serre est indiscernable d un I420 par la taille`() {
        val inferred = inferYuv420Layout(byteCount = 24, width = 4, height = 4)
        assertEquals("l'inférence ne peut pas voir l'entrelacement", tight(), inferred)

        val nv12 = Yuv420Layout(width = 4, height = 4, yRowStride = 4, uvRowStride = 4, uvPixelStride = 2)
        val data = buildYuv(nv12, yAt = { _, _ -> 76 }, u = 90, v = 240)
        val readAsPlanar = convert(inferred!!, data)
        val readAsNv12 = convert(nv12, data)
        assertFalse(
            "la lecture planaire d'un buffer NV12 doit différer de sa lecture correcte",
            readAsPlanar.contentEquals(readAsNv12),
        )
    }

    @Test
    fun `la taille minimale couvre les plans et leur padding`() {
        assertEquals(24, tight().requiredBytes())
        // Dernière ligne du plan Y paddée aussi : 8 * 4, puis 4 * 2 par plan de chroma.
        assertEquals(48, Yuv420Layout(4, 4, 8, 4).requiredBytes())
        // Semi-planaire : un seul plan de chroma entrelacé.
        assertEquals(24, Yuv420Layout(4, 4, 4, 4, 2).requiredBytes())
    }
}