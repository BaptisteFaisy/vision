package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions pures du décodeur H.265 ([chromaStepFor], [decoderOutputLayout]),
 * testées sur JVM. Le `MediaCodec` lui-même n'existe pas hors Android : seul
 * un faux décodeur l'éprouve, via `GlassesFrameCollectorTest`.
 */
class GlassesVideoDecoderTest {

    @Test
    fun `le planaire I420 a un pas de chroma de 1`() {
        assertEquals(1, chromaStepFor(COLOR_FORMAT_YUV420_PLANAR))
    }

    @Test
    fun `le semi-planaire NV12 a un pas de chroma de 2`() {
        assertEquals(2, chromaStepFor(COLOR_FORMAT_YUV420_SEMIPLANAR))
    }

    @Test
    fun `le flexible et l inconnu n ont pas de pas devine`() {
        // 0x7F000200 = COLOR_FormatYUV420Flexible : deviner le plan par buffer
        // produirait une image sur deux fausse, on jette au lieu de deviner.
        assertNull(chromaStepFor(0x7F000200))
        assertNull(chromaStepFor(0))
        assertNull(chromaStepFor(39))
    }

    @Test
    fun `la sortie planaire prend la moitie du pas en chroma`() {
        // 504x896 = VideoQuality.MEDIUM, pas de 512 : chroma à 256.
        assertEquals(
            Yuv420Layout(504, 896, 512, 256, 1),
            decoderOutputLayout(504, 896, 512, 896, COLOR_FORMAT_YUV420_PLANAR),
        )
    }

    @Test
    fun `la sortie semi-planaire garde le pas entier en chroma`() {
        assertEquals(
            Yuv420Layout(504, 896, 512, 512, 2),
            decoderOutputLayout(504, 896, 512, 896, COLOR_FORMAT_YUV420_SEMIPLANAR),
        )
    }

    @Test
    fun `la chroma planaire ne descend jamais sous la largeur utile`() {
        // Pas exotique de 2 sur 6 px de large : la moitié (1) ne couvrirait pas
        // les 3 échantillons de chroma, on remonte à 3.
        assertEquals(
            Yuv420Layout(6, 4, 6, 3, 1),
            decoderOutputLayout(6, 4, 6, 4, COLOR_FORMAT_YUV420_PLANAR),
        )
    }

    @Test
    fun `un pas plus court que l image est incomprehensible`() {
        assertNull(decoderOutputLayout(504, 896, 500, 896, COLOR_FORMAT_YUV420_PLANAR))
    }

    @Test
    fun `une tranche plus courte que l image est incomprehensible`() {
        assertNull(decoderOutputLayout(504, 896, 512, 800, COLOR_FORMAT_YUV420_PLANAR))
    }

    @Test
    fun `une tranche alignee est portee pas jetee`() {
        // 512x912 = alignement 16 courant du MediaCodec pour 504x896 : le
        // chroma suit à 256x456, le padding est ignoré à la conversion.
        assertEquals(
            Yuv420Layout(504, 896, 512, 256, 1, 912),
            decoderOutputLayout(504, 896, 512, 912, COLOR_FORMAT_YUV420_PLANAR),
        )
        assertEquals(
            Yuv420Layout(504, 896, 512, 512, 2, 912),
            decoderOutputLayout(504, 896, 512, 912, COLOR_FORMAT_YUV420_SEMIPLANAR),
        )
    }

    @Test
    fun `des cles manquantes retombent en serre`() {
        // `MediaFormat` sans `stride` ni `slice-height` (0) : on suppose le
        // buffer serré au lieu de jeter toutes les frames.
        assertEquals(
            Yuv420Layout(504, 896, 504, 252, 1, 896),
            decoderOutputLayout(504, 896, 0, 0, COLOR_FORMAT_YUV420_PLANAR),
        )
        assertEquals(
            Yuv420Layout(504, 896, 504, 504, 2, 896),
            decoderOutputLayout(504, 896, 0, 0, COLOR_FORMAT_YUV420_SEMIPLANAR),
        )
        // Partiel : la clé présente est gardée, la manquante retombe en serré.
        assertEquals(
            Yuv420Layout(504, 896, 504, 252, 1, 912),
            decoderOutputLayout(504, 896, 0, 912, COLOR_FORMAT_YUV420_PLANAR),
        )
        assertEquals(
            Yuv420Layout(504, 896, 512, 256, 1, 896),
            decoderOutputLayout(504, 896, 512, 0, COLOR_FORMAT_YUV420_PLANAR),
        )
    }

    @Test
    fun `la tranche paddee compte ses lignes de remplissage`() {
        val layout = Yuv420Layout(504, 896, 512, 256, 1, 912)
        // Y : 512x912, chroma : 2 plans de 256x456.
        assertEquals(512 * 912 + 2 * 256 * 456, layout.requiredBytes())
        assertEquals(512 * 912, layout.uOffset)
    }

    @Test
    fun `la tranche paddee se convertit comme la serree`() {
        // Même zone utile (Y=200, neutre U=V=128), padding à 0 : les deux
        // conversions doivent réussir et rendre les mêmes pixels.
        val tight = Yuv420Layout(4, 4, 4, 2, 1)
        val padded = Yuv420Layout(4, 4, 8, 4, 1, 6)
        val tightOut = IntArray(16)
        val paddedOut = IntArray(16)
        assertTrue(YuvConverter.yuv420ToArgb(fillUseful(tight), tight, tightOut))
        assertTrue(YuvConverter.yuv420ToArgb(fillUseful(padded), padded, paddedOut))
        assertArrayEquals(tightOut, paddedOut)
        val halfOut = IntArray(4)
        assertTrue(YuvConverter.yuv420ToArgbHalf(fillUseful(padded), padded, halfOut))
    }

    @Test
    fun `une tranche trop courte ne se convertit pas`() {
        val layout = Yuv420Layout(4, 4, 4, 2, 1, 2)
        assertFalse(YuvConverter.yuv420ToArgb(ByteArray(64), layout, IntArray(16)))
        assertFalse(YuvConverter.yuv420ToArgbHalf(ByteArray(64), layout, IntArray(4)))
    }

    @Test
    fun `un format de sortie inconnu est jete`() {
        assertNull(decoderOutputLayout(504, 896, 512, 896, 0x7F000200))
    }

    @Test
    fun `des dimensions nulles ne font pas une disposition`() {
        assertNull(decoderOutputLayout(0, 896, 512, 896, COLOR_FORMAT_YUV420_PLANAR))
        assertNull(decoderOutputLayout(504, 0, 512, 0, COLOR_FORMAT_YUV420_PLANAR))
    }

    @Test
    fun `le pont n attend jamais plus d une periode image`() {
        // Pire cas 1 + 40 = ~41 ms : l'ancien 50 + 300 = 350 ms était le seul
        // poste au-delà du budget 250 ms. Une frame manquée est conflée.
        assertEquals(1_000L, GlassesVideoDecoder.INPUT_TIMEOUT_US)
        assertEquals(40L, GlassesVideoDecoder.OUTPUT_WAIT_MS)
        assertEquals(15, GlassesVideoDecoder.OPERATING_RATE)
    }

    /** Frame compressée 504×896 : dimensions de production (VideoQuality.MEDIUM). */
    private fun compressedFrame(
        buffer: ByteBuffer = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1)),
        width: Int = 504,
        height: Int = 896,
    ) = VideoFrame(buffer, width, height, 2_000L, true, false)

    @Test
    fun `sans codec decode rend null sans lever`() {
        // Sur JVM il n'y a pas de MediaCodec : la création échoue, la frame est jetée.
        assertNull(GlassesVideoDecoder().decode(compressedFrame()))
    }

    @Test
    fun `trois creations ratees abandonnent, release repart de zero`() {
        val journal = mutableListOf<String>()
        val decoder = GlassesVideoDecoder(onLog = { journal += it })

        repeat(3) { assertNull(decoder.decode(compressedFrame())) }
        assertEquals(listOf("H.265 decoder given up after 3 failures"), journal)

        // Abandonné : la 4e frame est jetée sans nouvel essai ni nouveau journal.
        assertNull(decoder.decode(compressedFrame()))
        assertEquals(1, journal.size)

        // Libéré : l'abandon est effacé, le cycle de 3 recommence.
        decoder.release()
        repeat(3) { assertNull(decoder.decode(compressedFrame())) }
        assertEquals(2, journal.size)
    }

    @Test
    fun `dimensions nulles = jete sans essai ni compteur`() {
        val journal = mutableListOf<String>()
        val decoder = GlassesVideoDecoder(onLog = { journal += it })

        repeat(3) { assertNull(decoder.decode(compressedFrame(width = 0, height = 0))) }

        // Aucun essai de création : pas d'abandon après 3 frames dégénérées…
        assertTrue(journal.isEmpty())
        // …et le compteur est intact : 3 vraies frames l'abandonnent.
        repeat(3) { assertNull(decoder.decode(compressedFrame())) }
        assertEquals(1, journal.size)
    }

    @Test
    fun `stashCsd range sans lever vide comme chargee`() {
        val decoder = GlassesVideoDecoder()

        decoder.stashCsd(compressedFrame(buffer = ByteBuffer.wrap(ByteArray(0))))
        decoder.stashCsd(compressedFrame(buffer = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))))
    }

    /** Buffer à la taille de [layout], zone utile grise, padding à 0. */
    private fun fillUseful(layout: Yuv420Layout): ByteArray {
        val data = ByteArray(layout.requiredBytes())
        for (row in 0 until layout.height) {
            for (col in 0 until layout.width) {
                data[row * layout.yRowStride + col] = 200.toByte()
            }
        }
        for (row in 0 until layout.chromaHeight) {
            for (col in 0 until layout.chromaWidth) {
                data[layout.uOffset + row * layout.uvRowStride + col] = 128.toByte()
                data[layout.vOffset + row * layout.uvRowStride + col] = 128.toByte()
            }
        }
        return data
    }
}
