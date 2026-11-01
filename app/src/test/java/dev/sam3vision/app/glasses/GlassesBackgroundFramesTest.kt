package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.VideoFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Échantillonneur hors écran ([GlassesBackgroundFrames]) joué sur JVM : le
 * décodeur `MediaCodec` (inexistant hors Android) et les deux conversions
 * `Bitmap` sont injectés en faux — le chemin bitmap (flux JPEG ou brut
 * décodable) ne tourne que sur appareil, comme le rendu du direct.
 *
 * Ce que ces tests verrouillent : le routage (config brute → `csd-0`,
 * compressé → décodeur en Annex B, brut → tentative bitmap sans décodeur),
 * la déduplication par séquence et les gardes (vide, dimensions nulles).
 */
class GlassesBackgroundFramesTest {

    /** Faux décodeur : le compressé rend [image], les octets reçus sont relus. */
    private class FakeDecoder(
        var image: ArgbImage? = ArgbImage(IntArray(4), 2, 2, 0L),
    ) : GlassesVideoDecoder() {
        var stashed = 0
        var decoded = 0
        var lastBytes: ByteArray? = null

        override fun stashCsd(frame: VideoFrame) {
            stashed += 1
        }

        override fun decode(frame: VideoFrame): ArgbImage? {
            decoded += 1
            lastBytes = frame.payloadBytes()
            return image
        }

        override fun release() = Unit
    }

    private var bitmapAttempts = 0
    private var converted: ArgbImage? = null

    private fun sampler(decoder: FakeDecoder) = GlassesBackgroundFrames(
        decoder = decoder,
        tryBitmap = { bitmapAttempts += 1; null },
        argbToBitmap = { image -> converted = image; null },
    )

    private fun box(
        seq: Long = 1L,
        bytes: ByteArray = byteArrayOf(0, 0, 0, 1),
        width: Int = 504,
        height: Int = 896,
        compressed: Boolean = true,
        codecConfig: Boolean = false,
    ) = GlassesFrameBox(seq, GlassesFrame(bytes, width, height, 2_000L, compressed, codecConfig))

    @Test
    fun `meme poussee deux fois = decode une seule fois, la suivante repasse`() {
        val decoder = FakeDecoder()
        val pump = sampler(decoder)
        val same = box()

        assertNull(pump.bitmapOf(same))
        assertNull(pump.bitmapOf(same))
        assertEquals(1, decoder.decoded)
        assertNull(pump.bitmapOf(box(seq = 2L)))
        assertEquals(2, decoder.decoded)
    }

    @Test
    fun `config brute remise au decodeur et rien d autre`() {
        val decoder = FakeDecoder()

        assertNull(sampler(decoder).bitmapOf(box(compressed = false, codecConfig = true)))

        assertEquals(1, decoder.stashed)
        assertEquals(0, decoder.decoded)
        assertEquals(0, bitmapAttempts)
    }

    @Test
    fun `compresse part au decodeur en Annexe B puis au convertisseur`() {
        val decoder = FakeDecoder()

        assertNull(sampler(decoder).bitmapOf(box(bytes = byteArrayOf(0, 0, 0, 4, 0x65, 0x41, 0x42, 0x43))))

        assertEquals(1, decoder.decoded)
        assertEquals(
            listOf<Byte>(0, 0, 0, 1, 0x65, 0x41, 0x42, 0x43),
            decoder.lastBytes?.toList(),
        )
        assertEquals(decoder.image, converted)
    }

    @Test
    fun `brut non config = tentative bitmap, decodeur jamais touche`() {
        val decoder = FakeDecoder()

        assertNull(sampler(decoder).bitmapOf(box(compressed = false)))

        assertEquals(1, bitmapAttempts)
        assertEquals(0, decoder.decoded)
        assertEquals(0, decoder.stashed)
    }

    @Test
    fun `vide ou dimensions nulles = rien sans rien toucher`() {
        val decoder = FakeDecoder()
        val pump = sampler(decoder)

        assertNull(pump.bitmapOf(box(seq = 1L, bytes = byteArrayOf())))
        assertNull(pump.bitmapOf(box(seq = 2L, width = 0)))
        assertNull(pump.bitmapOf(box(seq = 3L, height = -1)))

        assertEquals(0, decoder.decoded)
        assertEquals(0, decoder.stashed)
        assertEquals(0, bitmapAttempts)
    }

    @Test
    fun `echec du decodeur = null`() {
        val decoder = FakeDecoder(image = null)

        assertNull(sampler(decoder).bitmapOf(box()))

        assertEquals(1, decoder.decoded)
        assertNull(converted)
    }
}
