package dev.sam3vision.glasses

import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Garde-fous de [GlassesFrameEncoder] testés sur JVM avec de vraies `VideoFrame`.
 *
 * `VideoFrame` est une data class Kotlin (`javap` sur `mwdat-camera` 1.0.1 :
 * `(ByteBuffer, int, int, long, boolean, boolean)`), donc instanciable hors
 * Android. Seuls les chemins qui **refusent** une frame sont exercés : ils
 * rendent la main avant `android.graphics.Bitmap`, qui n'existe pas en test
 * unitaire. La conversion YUV elle-même est couverte par [YuvConverterTest].
 */
class GlassesFrameEncoderTest {

    private val encoder = GlassesFrameEncoder()

    private fun frame(
        byteCount: Int,
        width: Int,
        height: Int,
        compressed: Boolean = false,
        codecConfig: Boolean = false,
    ): VideoFrame = VideoFrame(
        ByteBuffer.allocate(byteCount),
        width,
        height,
        1_000_000L,
        compressed,
        codecConfig,
    )

    @Test
    fun `une frame compressee ne produit pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 1_024, width = 4, height = 4, compressed = true)))
    }

    @Test
    fun `une frame de configuration codec ne produit pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 24, width = 4, height = 4, codecConfig = true)))
    }

    @Test
    fun `des dimensions nulles ne produisent pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 24, width = 0, height = 4)))
        assertNull(encoder.toJpeg(frame(byteCount = 24, width = 4, height = 0)))
    }

    @Test
    fun `un buffer vide ne produit pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 0, width = 4, height = 4)))
    }

    @Test
    fun `un buffer dont la taille ne colle a aucune disposition ne produit pas de JPEG`() {
        // 23 octets pour du 4x4 : la disposition serrée en demande 24.
        assertNull(inferYuv420Layout(byteCount = 23, width = 4, height = 4))
        assertNull(encoder.toJpeg(frame(byteCount = 23, width = 4, height = 4)))
    }

    @Test
    fun `lire le buffer ne deplace pas celui du SDK`() {
        val source = frame(byteCount = 23, width = 4, height = 4)
        assertNull(encoder.toJpeg(source))
        // Le SDK réutilise son buffer : l'encoder ne doit pas le consommer.
        assertEquals(0, source.buffer.position())
        assertEquals(23, source.buffer.limit())
    }
}