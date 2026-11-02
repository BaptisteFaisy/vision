package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.speech.VoicePack
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * FIX 4 — coupes mortes sécurisées contre réactivation : `stop()` ne termine
 * jamais (`onComplete` ne part pas), donc chaque coupe rend le bus
 * ([VoiceBus.onSpeechIdle]) — sinon une réactivation coincerait toute la voix
 * douce (niveau cru occupé pour toujours). Preuve : stop-puis-post-accepté.
 */
class Fix4DeadCutGuardTest {

    private class FakeSink : ClipSink {
        var stops = 0

        override suspend fun playFile(file: File): Result<Unit> = Result.success(Unit)

        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> = Result.success(Unit)

        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> =
            Result.success(PlaybackEnd.Done)

        override fun stop() {
            stops += 1
        }
    }

    private class FakeSpeaker : VoiceBusSpeaker {
        val said = mutableListOf<Pair<Int, String>>()

        override fun idle(): Boolean = true

        override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
            said += level to text
        }

        override fun cut() = Unit
    }

    private val speaker = FakeSpeaker()

    @Before
    fun brancher() {
        VoiceBus.reset()
        VoiceBus.onIdle = null
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.onIdle = null
        VoiceBus.reset()
    }

    private fun clipPlayer(sink: FakeSink) = IseeClipPlayer(
        clips = VoicePack(
            filesDir = Files.createTempDirectory("fix4-clips").toFile(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        ),
        voiceId = { "voix-test" },
        sink = sink,
    )

    @Test
    fun `stop du lecteur de clips libere le bus`() {
        val sink = FakeSink()
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(1, speaker.said.size)

        clipPlayer(sink).stop()

        assertEquals(1, sink.stops)
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.FALLBACK to "repli", speaker.said.last())
    }

    @Test
    fun `alerte securite libere le bus`() {
        val driver = SafetyTestDriver()
        val stops = mutableListOf<Unit>()
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(1, speaker.said.size)

        testSafetyVoice(driver, stops).speak("Danger.")

        assertEquals(1, stops.size)
        assertEquals(1, driver.calls.size)
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.FALLBACK to "repli", speaker.said.last())
    }

    @Test
    fun `alerte blanche ne coupe ni ne libere`() {
        val driver = SafetyTestDriver()
        val stops = mutableListOf<Unit>()
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")

        testSafetyVoice(driver, stops).speak("   ")

        assertEquals(0, stops.size)
        assertEquals(0, driver.calls.size)
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(1, speaker.said.size)
    }
}
