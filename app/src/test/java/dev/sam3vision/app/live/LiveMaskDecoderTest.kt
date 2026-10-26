package dev.sam3vision.app.live

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Masques du direct : le décodage et le recoloriage se font **hors thread principal**
 * (60-80 ms par frame sur la boucle de composition), le poste `overlays` de la
 * chronologie est posé juste après, et un masque illisible est omis sans casser la
 * frame.
 */
class LiveMaskDecoderTest {

    private fun frame(vararg masks: String, encoding: String = "png") = LiveFrame(
        width = 100,
        height = 100,
        elapsedMs = 12L,
        objects = masks.mapIndexed { index, mask ->
            SegObject("objet$index", 0.9f, listOf(1f, 1f, 20f, 20f), mask)
        },
        maskEncoding = encoding,
    )

    @Test
    fun `le decodage part sur un autre thread que l appelant`() = runTest {
        val caller = Thread.currentThread().name
        var decoderThread = ""
        val decoder = LiveMaskDecoder<String>(
            paint = { mask, _, _, _ -> decoderThread = Thread.currentThread().name; mask },
            colorAt = { 0 },
            alpha = 115,
        )
        val drawn = decoder.decode(frame("masque-a"))
        assertEquals(listOf("masque-a"), drawn)
        assertNotEquals(caller, decoderThread)
        assertTrue(decoderThread.contains("DefaultDispatcher"))
    }

    @Test
    fun `un masque illisible est omis sans casser les autres`() = runTest {
        val decoder = LiveMaskDecoder<String>(
            paint = { mask, _, _, _ -> if (mask == "casse") null else mask },
            colorAt = { 0 },
            alpha = 115,
        )
        val drawn = decoder.decode(frame("bon", "casse", "bon-2"))
        assertEquals(listOf("bon", "bon-2"), drawn)
    }

    @Test
    fun `une frame sans masque lisible rend une liste vide`() = runTest {
        var decoded = false
        val decoder = LiveMaskDecoder<String>(
            paint = { _, _, _, _ -> null },
            colorAt = { 0 },
            alpha = 115,
            onDecoded = { decoded = true },
        )
        assertEquals(emptyList<String>(), decoder.decode(frame("casse")))
        assertTrue(decoded)
    }

    @Test
    fun `chaque objet recoit sa couleur dans l ordre`() = runTest {
        val colors = mutableListOf<Int>()
        val decoder = LiveMaskDecoder<String>(
            paint = { mask, _, argb, _ -> colors += argb; mask },
            colorAt = { index -> index * 10 },
            alpha = 115,
        )
        decoder.decode(frame("a", "b", "c"))
        assertEquals(listOf(0, 10, 20), colors)
    }

    @Test
    fun `l encodage de la frame est transmis au decodeur`() = runTest {
        var encoding = ""
        val decoder = LiveMaskDecoder<String>(
            paint = { mask, enc, _, _ -> encoding = enc; mask },
            colorAt = { 0 },
            alpha = 115,
        )
        decoder.decode(frame("a", encoding = "bits"))
        assertEquals("bits", encoding)
    }

    @Test
    fun `le poste overlays est pose apres le coloriage`() = runTest {
        val events = mutableListOf<String>()
        val decoder = LiveMaskDecoder<String>(
            paint = { mask, _, _, _ -> events += "paint"; mask },
            colorAt = { 0 },
            alpha = 115,
            onDecoded = { events += "decoded" },
        )
        decoder.decode(frame("a"))
        assertEquals(listOf("paint", "decoded"), events)
    }
}