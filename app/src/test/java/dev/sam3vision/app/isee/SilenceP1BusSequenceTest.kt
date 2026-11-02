package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbEdge
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 silence T-B2 : `BusSpeaker.say` avec clips joue la séquence
 * (`speakSequence`) et ne synthétise jamais — même au niveau GPS, où la
 * proximité synthétiserait si elle était atteinte (preuve qu'elle ne
 * l'est pas). Témoin : sans clips, la porte GPS tait toujours (zéro
 * réseau), le lecteur de clips reste muet.
 */
class SilenceP1BusSequenceTest {

    /** Puits clips : tranches en file comptées, lectures simples comptées, coupes comptées. */
    private class SeqSink : ClipSink {
        var queued = 0
        var files = 0
        var stops = 0

        override suspend fun playFile(file: File): Result<Unit> {
            files += 1
            return Result.success(Unit)
        }

        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> {
            files += 1
            return Result.success(Unit)
        }

        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> {
            queued += 1
            return Result.success(PlaybackEnd.Done)
        }

        override fun stop() {
            stops += 1
        }
    }

    /** Proximité comptant les synthèses (zéro attendu sur le chemin séquence). */
    private class Rig(val sink: SeqSink = SeqSink()) {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val said = mutableListOf<String>()

        fun tempDir(): File = Files.createTempDirectory("silence-p1-b2").toFile()

        fun pack(): VoicePack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )

        fun proximity(): ProximityVoice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
            onSpeaking = { if (it.isNotEmpty()) said += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.success("$text-audio".toByteArray())
            },
        )

        fun clips(): IseeClipPlayer = IseeClipPlayer(pack(), { "voix-test" }, sink, onSpeaking = { spoken += it })
    }

    @Before
    fun brancher() {
        VoiceBus.reset()
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
    }

    @Test
    fun `say avec clips = sequence jouee, zero synthese`() = runTest {
        val rig = Rig()
        val wish = hazardWish(HazardCue("Attention, person, at 3 o'clock.", meters = 8f, kind = HazardKind.PERSON))
        val clips = wish?.clips.orEmpty()
        assertTrue(clips.isNotEmpty())
        val speaker = BusSpeaker(this, SpeechPlayer(File(rig.tempDir(), "bus")), rig.clips(), rig.proximity())

        speaker.say(OutdoorLevel.HUMAN, wish?.text.orEmpty(), clipId = null, clips = clips)
        advanceUntilIdle()

        assertEquals(3, rig.sink.queued)
        assertEquals(0, rig.sink.files)
        assertEquals(listOf("Person. 3 o'clock. 10 meters."), rig.spoken)
        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.said.isEmpty())
    }

    @Test
    fun `say avec clips au niveau GPS = proximite intouchee`() = runTest {
        val rig = Rig()
        val wish = hazardWish(HazardCue("Attention, person, at 3 o'clock.", meters = 8f, kind = HazardKind.PERSON))
        val speaker = BusSpeaker(this, SpeechPlayer(File(rig.tempDir(), "bus")), rig.clips(), rig.proximity())

        speaker.say(OutdoorLevel.GPS_EVENT, wish?.text.orEmpty(), clipId = null, clips = wish?.clips.orEmpty())
        advanceUntilIdle()

        assertEquals(3, rig.sink.queued)
        assertTrue(rig.attempts.isEmpty())
    }

    @Test
    fun `say bordure sens connu = sequence jouee, zero synthese`() = runTest {
        val rig = Rig()
        val wish = curbWish(CurbCue("Curb detected, at 12 o'clock.", meters = 3f, edge = CurbEdge.Down))
        val clips = wish?.clips.orEmpty()
        assertEquals(listOf(IseeClipsEn.CURB_DOWN, IseeClipsEn.CLOCK_12), clips)
        val speaker = BusSpeaker(this, SpeechPlayer(File(rig.tempDir(), "bus")), rig.clips(), rig.proximity())

        speaker.say(OutdoorLevel.CURB, wish?.text.orEmpty(), clipId = null, clips = clips)
        advanceUntilIdle()

        assertEquals(2, rig.sink.queued)
        assertEquals(0, rig.sink.files)
        assertEquals(listOf("Curb down. 12 o'clock."), rig.spoken)
        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.said.isEmpty())
    }

    @Test
    fun `temoin sans clips = porte GPS, zero reseau, clips muets`() = runTest {
        val rig = Rig()
        val speaker = BusSpeaker(this, SpeechPlayer(File(rig.tempDir(), "bus")), rig.clips(), rig.proximity())

        speaker.say(OutdoorLevel.HUMAN, "Attention, barrier, at 2 o'clock.", clipId = null, clips = emptyList())
        advanceUntilIdle()

        assertEquals(0, rig.sink.queued)
        assertEquals(0, rig.sink.files)
        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.said.isEmpty())
    }
}
