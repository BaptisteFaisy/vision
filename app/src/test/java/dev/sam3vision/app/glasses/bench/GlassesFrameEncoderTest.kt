package dev.sam3vision.app.glasses.bench

import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Câblage des garde-fous de [GlassesFrameEncoder], testé sur JVM avec de vraies
 * `VideoFrame` (`javap` sur `mwdat-camera` 1.0.1 : data class Kotlin
 * `(ByteBuffer, int, int, long, boolean, boolean)`, donc instanciable hors Android).
 *
 * La **table de décision** ([FrameVerdict], priorité du drapeau « compressé »
 * comprise) est prouvée cas par cas par `SessionPolicyTest` : la rejouer ici
 * n'ajouterait rien. Ce fichier ne prouve que ce que seul l'encodeur peut montrer
 * — que le verdict est réellement consulté **avant** tout `Bitmap` (absent des
 * tests unitaires), que la lecture du buffer refuse une frame vide ou de taille
 * impossible, et que le buffer du SDK survit à la lecture. La conversion YUV est
 * couverte par `YuvConverterTest`.
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

    /**
     * Les trois verdicts de rejet, vus depuis l'encodeur : chacun doit rendre la
     * main avant `android.graphics.Bitmap`. Si le câblage du verdict disparaissait,
     * la table de `SessionPolicyTest` resterait verte et l'encodeur, non.
     */
    @Test
    fun `les frames que le verdict ecarte ne produisent pas de JPEG`() {
        // 1 Ko compressé : le cas réel, le SDK livre du H.265 par défaut.
        assertNull(encoder.toJpeg(frame(byteCount = 1_024, width = 4, height = 4, compressed = true)))
        assertNull(encoder.toJpeg(frame(byteCount = 24, width = 4, height = 4, codecConfig = true)))
        assertNull(encoder.toJpeg(frame(byteCount = 24, width = 0, height = 4)))
    }

    /** Ici le verdict dit « encoder » : c'est la lecture du buffer qui doit refuser. */
    @Test
    fun `un buffer vide ne produit pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 0, width = 4, height = 4)))
    }

    /** 23 octets pour du 4x4 : aucune disposition plausible (la serrée en demande 24). */
    @Test
    fun `un buffer sans disposition plausible ne produit pas de JPEG`() {
        assertNull(encoder.toJpeg(frame(byteCount = 23, width = 4, height = 4)))
    }

    @Test
    fun `lire le buffer ne deplace pas celui du SDK`() {
        val source = frame(byteCount = 23, width = 4, height = 4)
        assertNull(encoder.toJpeg(source))
        // Le SDK réutilise son buffer : l'encodeur le lit par une vue dupliquée.
        assertEquals(0, source.buffer.position())
        assertEquals(23, source.buffer.limit())
    }
}