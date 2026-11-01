package dev.sam3vision.app.isee

import dev.sam3vision.core.mask.DecodeSplit
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
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
    fun `le levier se coupe a chaud`() {
        val painter = MaskPainter({ 0xFFFF0000.toInt() }, 128)
        painter.overlays(frame)
        assertTrue(painter.lastDecode.known)

        painter.measureDecode = false
        painter.overlays(frame)

        assertEquals(DecodeSplit.EMPTY, painter.lastDecode)
    }
}
