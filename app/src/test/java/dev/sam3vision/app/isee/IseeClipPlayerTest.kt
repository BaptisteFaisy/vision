package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.speech.VoicePack
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lecteur des clips anglais : clip local joué sans aucun réseau, silence si
 * le clip manque (jamais de synthèse de repli), phrases composées en file
 * sans chevauchement, urgent coupe, le reste attend.
 */
class IseeClipPlayerTest {

    private class FakeSink(
        var queued: Result<PlaybackEnd> = Result.success(PlaybackEnd.Done),
        var file: Result<Unit> = Result.success(Unit),
    ) : ClipSink {
        val playedFiles = mutableListOf<String>()
        val playedSlices = mutableListOf<ByteArray>()
        var stops = 0

        override suspend fun playFile(file: File): Result<Unit> {
            playedFiles += file.name
            return this.file
        }

        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
            playedSlices += bytes
            return queued
        }

        override fun stop() {
            stops += 1
        }
    }

    private fun pack(bundled: ByteArray? = "faux-mp3".toByteArray()): VoicePack = VoicePack(
        filesDir = Files.createTempDirectory("clips-en").toFile(),
        packVoiceId = { "voix-test" },
        bundledClip = { bundled },
    )

    private fun player(
        sink: FakeSink,
        bundled: ByteArray? = "faux-mp3".toByteArray(),
        spoken: MutableList<String> = mutableListOf(),
    ) = IseeClipPlayer(
        clips = pack(bundled),
        voiceId = { "voix-test" },
        sink = sink,
        onSpeaking = { spoken += it },
    )

    @Test
    fun `clip présent, joué en local avec son texte exact`() = runTest {
        val sink = FakeSink()
        val spoken = mutableListOf<String>()

        val played = player(sink, spoken = spoken).speak("verdict_dont_cross", urgent = false)

        assertTrue(played)
        assertEquals(listOf("vp_verdict_dont_cross_en.mp3"), sink.playedFiles)
        assertEquals(listOf("Don't cross."), spoken)
        assertEquals(0, sink.stops)
    }

    @Test
    fun `urgent coupe avant de jouer`() = runTest {
        val sink = FakeSink()

        player(sink).speak("car", urgent = true)

        assertEquals(1, sink.stops)
        assertEquals(listOf("vp_car_en.mp3"), sink.playedFiles)
    }

    @Test
    fun `clip inconnu, silence sans toucher au lecteur`() = runTest {
        val sink = FakeSink()
        val spoken = mutableListOf<String>()

        val played = player(sink, spoken = spoken).speak("n_existe_pas", urgent = true)

        assertFalse(played)
        assertTrue(sink.playedFiles.isEmpty())
        assertTrue(spoken.isEmpty())
        assertEquals(0, sink.stops)
    }

    @Test
    fun `clip manquant sur disque, silence sans synthèse`() = runTest {
        val sink = FakeSink()
        val spoken = mutableListOf<String>()

        val played = player(sink, bundled = null, spoken = spoken)
            .speak("verdict_cross", urgent = false)

        assertFalse(played)
        assertTrue(sink.playedFiles.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `lecteur en échec, silence signalé`() = runTest {
        val sink = FakeSink(file = Result.failure(IllegalStateException("hs")))
        val spoken = mutableListOf<String>()

        val played = player(sink, spoken = spoken).speak("cars", urgent = false)

        assertFalse(played)
        assertEquals(listOf("Cars.", ""), spoken)
    }

    @Test
    fun `phrase composée enchaînée dans l ordre`() = runTest {
        val sink = FakeSink()
        val spoken = mutableListOf<String>()
        val ids = listOf("human_person", "clock_3", "meters_5")

        val played = player(sink, spoken = spoken).speakSequence(ids, urgent = false)

        assertTrue(played)
        assertEquals(3, sink.playedSlices.size)
        assertEquals(listOf("Person. 3 o'clock. 5 meters."), spoken)
    }

    @Test
    fun `phrase composée avec un clip manquant, rien joué`() = runTest {
        val sink = FakeSink()
        val spoken = mutableListOf<String>()
        val full = VoicePack(
            filesDir = Files.createTempDirectory("clips-partiels").toFile(),
            packVoiceId = { "voix-test" },
            bundledClip = { name ->
                if (name == "vp_clock_3_en.mp3") null else "faux-mp3".toByteArray()
            },
        )
        val player = IseeClipPlayer(full, { "voix-test" }, sink, { spoken += it })

        val played = player.speakSequence(
            listOf("human_person", "clock_3", "meters_5"), urgent = false
        )

        assertFalse(played)
        assertTrue(sink.playedSlices.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `phrase composée vide ou coupée, faux`() = runTest {
        assertFalse(player(FakeSink()).speakSequence(emptyList(), urgent = false))

        val sink = FakeSink(queued = Result.success(PlaybackEnd.Stopped))
        val spoken = mutableListOf<String>()
        val played = player(sink, spoken = spoken)
            .speakSequence(listOf("found", "keys"), urgent = false)

        assertFalse(played)
        assertEquals(1, sink.playedSlices.size)
        assertEquals(listOf("Found. Keys.", ""), spoken)
    }
}
