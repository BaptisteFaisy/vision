package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.VideoFrame
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pompe de production ([GlassesFrameCollector]) jouée sur JVM : le décodeur
 * `MediaCodec` (inexistant hors Android), l'horloge et l'encodage JPEG
 * (`Bitmap`) sont injectés en faux — seul le vrai chemin YUV brut
 * ([videoFrameToArgb]) tourne ici, avec une frame 2×2 construite à la main.
 *
 * Ce que ces tests verrouillent : un throttling d'~1 frame/1,5 s par rejet
 * (pas d'attente qui suspendrait le flux), le routage compressé → décodeur /
 * brut → YUV / config → `csd-0`, et le silence documenté de chaque jet
 * (journalisé, jamais levé).
 */
class GlassesFrameCollectorTest {

    /** Faux décodeur : le compressé rend [image] (ou `null` si [failDecode]). */
    private class FakeDecoder(
        var image: ArgbImage? = ArgbImage(IntArray(4), 2, 2, 0L),
        var failDecode: Boolean = false,
    ) : GlassesVideoDecoder() {
        var stashed = 0
        var decoded = 0
        var released = false

        override fun stashCsd(frame: VideoFrame) {
            stashed += 1
        }

        override fun decode(frame: VideoFrame): ArgbImage? {
            decoded += 1
            return if (failDecode) null else image
        }

        override fun release() {
            released = true
        }
    }

    private var now = 100_000L
    private val frames = mutableListOf<JpegFrame>()
    private val journal = mutableListOf<String>()

    private fun collector(decoder: FakeDecoder) = GlassesFrameCollector(
        decoder = decoder,
        onFrame = { frames += it },
        onLog = { journal += it },
        nowMs = { now },
        encode = { image, _ -> JpegFrame("b64-${image.width}x${image.height}", image.width, image.height, 1L) },
    )

    /** Frame brute 2×2 I420 valide (4 Y + 1 U + 1 V) : le vrai YUV la convertit. */
    private fun rawFrame(): VideoFrame = VideoFrame(
        ByteBuffer.wrap(byteArrayOf(16, 16, 16, 16, -128, -128)),
        2, 2, 1_000L, false, false,
    )

    private fun compressedFrame(config: Boolean = false): VideoFrame = VideoFrame(
        ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1)),
        504, 896, 2_000L, true, config,
    )

    private fun csdFrame(): VideoFrame = VideoFrame(
        ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)),
        504, 896, 0L, false, true,
    )

    @Test
    fun `une frame par fenetre passe le reste est jete sans attendre`() = runBlocking {
        val video = flow {
            emit(rawFrame())
            emit(rawFrame())
            now += FRAME_THROTTLE_MS
            emit(rawFrame())
        }

        collector(FakeDecoder()).run(video)

        assertEquals("2 frames dues sur 3", 2, frames.size)
        assertTrue(journal.any { it.startsWith("frame transmise") })
    }

    @Test
    fun `la config codec nourrit le decodeur hors etranglement`() = runBlocking {
        // Même instant pour les deux : l'étranglement n'en laisserait passer
        // qu'une si la config y passait — or elle n'y passe pas.
        collector(FakeDecoder()).run(flowOf(csdFrame(), csdFrame()))

        assertTrue("aucune image à émettre", frames.isEmpty())
        assertTrue("aucun décodage demandé", journal.none { it.contains("décodable") })
    }

    @Test
    fun `la config codec est remise au decodeur et rien d autre`() = runBlocking {
        val decoder = FakeDecoder()

        collector(decoder).run(flowOf(csdFrame()))

        assertEquals(1, decoder.stashed)
        assertEquals(0, decoder.decoded)
        assertTrue(frames.isEmpty())
    }

    @Test
    fun `le brut passe par le YUV partage sans toucher au decodeur`() = runBlocking {
        val decoder = FakeDecoder()

        collector(decoder).run(flowOf(rawFrame()))

        assertEquals(0, decoder.decoded)
        assertEquals(listOf("b64-2x2"), frames.map { it.base64 })
    }

    @Test
    fun `le compresse decode est transmis tel quel`() = runBlocking {
        val decoder = FakeDecoder()

        collector(decoder).run(flowOf(compressedFrame()))

        assertEquals(1, decoder.decoded)
        assertEquals(listOf("b64-2x2"), frames.map { it.base64 })
    }

    @Test
    fun `un decodage en echec tait la frame en journalisant`() = runBlocking {
        val decoder = FakeDecoder(failDecode = true)

        collector(decoder).run(flowOf(compressedFrame()))

        assertTrue("rien n'est émis", frames.isEmpty())
        assertEquals(listOf("frame non décodable : ignorée"), journal)
    }

    @Test
    fun `des dimensions nulles sont journalisees pas tues`() = runBlocking {
        val decoder = FakeDecoder()
        val broken = VideoFrame(ByteBuffer.wrap(byteArrayOf(1)), 0, 480, 0L, false, false)

        collector(decoder).run(flowOf(broken))

        assertTrue(frames.isEmpty())
        assertEquals(listOf("frame non décodable : ignorée"), journal)
    }

    @Test
    fun `un encodage en echec tait la frame en journalisant`() = runBlocking {
        val muet = GlassesFrameCollector(
            decoder = FakeDecoder(),
            onFrame = { frames += it },
            onLog = { journal += it },
            nowMs = { now },
            encode = { _, _ -> null },
        )

        muet.run(flowOf(rawFrame()))

        assertTrue(frames.isEmpty())
        assertEquals(listOf("frame non encodable : ignorée"), journal)
    }

    @Test
    fun `close libere le decodeur`() {
        val decoder = FakeDecoder()

        collector(decoder).close()

        assertTrue(decoder.released)
    }
}
