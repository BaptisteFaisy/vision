package dev.sam3vision.app.isee

import dev.sam3vision.core.mask.DecodeSplit
import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.alphaLut
import dev.sam3vision.core.mask.colorizeGrayInto
import dev.sam3vision.core.mask.upscaleNearest
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sonde de décodage du peintre : datée quand le levier est allumé, muette quand il est
 * éteint. Sur JVM, `Bitmap.createBitmap` rend `null` (stubs Android) : les overlays sont
 * vides, mais le décodage RLE et l'upscale sont purs — la sonde les date vraiment.
 */
class MaskPainterTest {

    /** Objet RLE d'une grille 4 × 3, ramenée à la frame 8 × 6 par l'upscale. */
    private val rleObject = SegObject(
        label = "personne",
        score = 0.9f,
        box = listOf(0f, 0f, 8f, 6f),
        maskPngB64 = "AgMH",
        maskEncoding = MASK_ENCODING_RLE,
        maskScale = 0.5f,
    )
    private val frame = LiveFrame(width = 8, height = 6, elapsedMs = 10, objects = listOf(rleObject))

    @Test
    fun `allume la sonde date base64 image et upscale`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)

        painter.overlays(frame)
        val split = painter.lastDecode

        assertTrue(split.known)
        assertTrue("b64 daté", split.b64Ms >= 0)
        assertTrue("runs datés", split.imgMs >= 0)
        assertTrue("upscale daté", split.upMs >= 0)
        assertEquals("le total vient de noteDecoded, pas des tampons", -1L, split.totalMs)
    }

    @Test
    fun `eteint la sonde ne date rien`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128, measureDecode = false)

        painter.overlays(frame)
        painter.noteDecoded(5, 0)

        assertEquals(DecodeSplit.EMPTY, painter.lastDecode)
        assertFalse(painter.lastDecode.known)
    }

    @Test
    fun `noteDecoded complete le total sans toucher les postes`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        painter.overlays(frame)
        val before = painter.lastDecode

        painter.noteDecoded(5, 1)

        assertEquals(5L, painter.lastDecode.totalMs)
        assertEquals(before.b64Ms, painter.lastDecode.b64Ms)
        assertEquals(before.imgMs, painter.lastDecode.imgMs)
    }

    @Test
    fun `masque illisible saute avec sa raison`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        val none = SegObject(
            label = "stairs", score = 0.5f, box = listOf(0f, 0f, 8f, 6f),
            maskPngB64 = "", maskEncoding = MASK_ENCODING_NONE,
        )
        val mixed = frame.copy(objects = listOf(rleObject, none))

        painter.overlays(mixed)

        assertEquals(listOf(SkippedMask("stairs", MASK_ENCODING_NONE, 0)), painter.lastSkipped)
        assertEquals("stairs(none,0c)", skippedSummary(painter.lastSkipped))
    }

    @Test
    fun `resume borne a six sautes`() {
        val skipped = List(7) { SkippedMask("a$it", "rle", it) }

        assertEquals("a0(rle,0c),a1(rle,1c),a2(rle,2c),a3(rle,3c),a4(rle,4c),a5(rle,5c)", skippedSummary(skipped))
        assertEquals("", skippedSummary(emptyList()))
    }

    @Test
    fun `le levier se coupe a chaud`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        painter.overlays(frame)
        assertTrue(painter.lastDecode.known)

        painter.measureDecode = false
        painter.overlays(frame)

        assertEquals(DecodeSplit.EMPTY, painter.lastDecode)
    }

    @Test
    fun `lastGrays expose un gris par objet a la taille de la frame`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)

        painter.overlays(frame)
        val grays = painter.lastGrays

        assertEquals(1, grays.size)
        assertEquals(8, grays[0]?.width)
        assertEquals(6, grays[0]?.height)
    }

    @Test
    fun `fusion identique a upscale puis coloriage au pixel pres`() {
        val rng = kotlin.random.Random(1234)
        val lut = alphaLut(0xFFFF0000.toInt(), 128)
        val dims = listOf(
            Triple(4, 3, 8 to 6),
            Triple(5, 5, 11 to 7),
            Triple(1, 1, 3 to 2),
            Triple(7, 4, 7 to 4),
            Triple(13, 9, 29 to 15),
        )
        for ((sw, sh, target) in dims) {
            val (fw, fh) = target
            val small = GrayMask(sw, sh, ByteArray(sw * sh) { rng.nextInt(256).toByte() })
            val gray = ByteArray(fw * fh)
            val scratch = IntArray(fw * fh)

            upscaleAndColorizeInto(small, fw, fh, lut, axisTable(fw, sw), axisTable(fh, sh), gray, scratch)

            val expected = upscaleNearest(small, fw, fh)
            assertNotNull("upscale $sw×$sh → $fw×$fh", expected)
            val painted = IntArray(fw * fh)
            colorizeGrayInto(expected!!, lut, painted)
            assertArrayEquals("gris $sw×$sh → $fw×$fh", expected.pixels, gray)
            assertArrayEquals("colorié $sw×$sh → $fw×$fh", painted, scratch)
        }
    }

    @Test
    fun `fusion sans gris identique au colorie seul`() {
        val rng = kotlin.random.Random(7)
        val lut = alphaLut(0xFF00FF00.toInt(), 200)
        val small = GrayMask(5, 4, ByteArray(20) { rng.nextInt(256).toByte() })
        val scratch = IntArray(11 * 9)

        upscaleAndColorizeInto(small, 11, 9, lut, axisTable(11, 5), axisTable(9, 4), null, scratch)

        val painted = IntArray(11 * 9)
        val grown = upscaleNearest(small, 11, 9)
        assertNotNull(grown)
        colorizeGrayInto(grown!!, lut, painted)
        assertArrayEquals(painted, scratch)
    }

    @Test
    fun `table identite quand cible egale source`() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), axisTable(5, 5))
    }

    @Test
    fun `poly garde son gris reduit, rle garde sa pleine frame`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        val poly = SegObject(
            label = "mur", score = 0.8f, box = listOf(0f, 0f, 8f, 6f),
            maskPngB64 = "", maskEncoding = MASK_ENCODING_POLY, maskScale = 0.5f,
            maskPoly = listOf(listOf(0, 0, 3, 0, 3, 2, 0, 2)),
        )
        val mixed = frame.copy(objects = listOf(rleObject, poly))

        painter.overlays(mixed)
        val grays = painter.lastGrays

        assertEquals(2, grays.size)
        assertEquals(8, grays[0]?.width)
        assertEquals(6, grays[0]?.height)
        assertEquals("poly : gris à sa grille, ignoré en aval", 4, grays[1]?.width)
        assertEquals(3, grays[1]?.height)
        assertEquals(0, painter.lastSkipped.size)
    }

    @Test
    fun `grille plus grande que la frame garde le chemin historique`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        // runs 100 + 92 = 192 = 16 × 12, grille 2× la frame 8 × 6.
        val big = SegObject(
            label = "mur", score = 0.8f, box = listOf(0f, 0f, 8f, 6f),
            maskPngB64 = "ZFw=", maskEncoding = MASK_ENCODING_RLE, maskScale = 2f,
        )

        painter.overlays(frame.copy(objects = listOf(big)))
        val grays = painter.lastGrays

        assertEquals(16, grays[0]?.width)
        assertEquals(12, grays[0]?.height)
        assertEquals(0, painter.lastSkipped.size)
    }
}
