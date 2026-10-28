package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `une tranche plus haute que l image est jetee pas devinee`() {
        // Yuv420Layout ne porte pas de hauteur de tranche : recaler le chroma
        // à l'aveugle décalerait l'image d'un nombre inconnu de lignes.
        assertNull(decoderOutputLayout(504, 896, 512, 912, COLOR_FORMAT_YUV420_PLANAR))
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
}
