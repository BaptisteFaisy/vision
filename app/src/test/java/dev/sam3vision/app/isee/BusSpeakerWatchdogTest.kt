package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vigile du bus vocal ([BusSpeaker]) : une parole coincée (synthèse ou
 * lecteur figés, observé sur appareil : paroles acceptées sans issue, niveau
 * actif épinglé, plus ni voix ni texte) est jetée à son budget et rend le
 * bus — la parole suivante parle. Temps virtuel (`runTest`), faux puits.
 */
class BusSpeakerWatchdogTest {

    /** Puits qui fige la lecture (`hang = true`) ou la réussit aussitôt. */
    private class FakeSink(var hang: Boolean = true) : ClipSink {
        val slices: MutableList<String> = mutableListOf()
        override suspend fun playFile(file: File): Result<Unit> = Result.success(Unit)
        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> {
            slices += file.name
            if (hang) CompletableDeferred<Result<Unit>>().await()
            return Result.success(Unit)
        }
        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> =
            Result.success(PlaybackEnd.Done)
        override fun stop() = Unit
    }

    private class Rig(val sink: FakeSink = FakeSink()) {
        val dir: File = Files.createTempDirectory("vigile").toFile()
        val player = SpeechPlayer(dir)
        val voiceId = "rachel-test"
        val pack = VoicePack(
            filesDir = dir,
            packVoiceId = { voiceId },
            bundledClip = { null },
        )
        val spoken: MutableList<String> = mutableListOf()
        val clips = IseeClipPlayer(pack, { voiceId }, sink, spoken::add)
        val proximity = ProximityVoice(player, { Settings.Default })

        init {
            pack.put("guide_left", SpeechLanguage.En, voiceId, "audio".toByteArray())
        }
    }

    @Test
    fun `parole coincee jetee au budget, bus rendu, suivante parle`() = runTest {
        val rig = Rig()
        val released = mutableListOf<Boolean>()
        val oldSpeaker = VoiceBus.speaker
        val oldIdle = VoiceBus.onIdle
        try {
            VoiceBus.reset()
            // backgroundScope : les superviseurs (25 s virtuelles) meurent en fin de test.
            val bus = BusSpeaker(backgroundScope, rig.player, rig.clips, rig.proximity, sayTimeoutMs = 1_000L)
            VoiceBus.speaker = bus
            VoiceBus.onIdle = { released += true }

            // Parole 1 : le puits fige la lecture, la parole tient le mutex.
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()
            assertEquals(1, rig.sink.slices.size)

            // Parole 2 (même niveau, acceptée) : s'empile derrière la coincée.
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()

            // Le budget passe : les deux paroles périmées sont jetées, le bus rendu.
            advanceTimeBy(1_500L)
            runCurrent()
            assertTrue(released.isNotEmpty())
            assertEquals(1, rig.sink.slices.size)

            // Parole 3 (puits réparé) : parle aussitôt — le bus n'est pas coincé.
            rig.sink.hang = false
            VoiceBus.post(OutdoorLevel.HUMAN, "Left.", "guide_left")
            runCurrent()
            assertEquals(2, rig.sink.slices.size)
        } finally {
            VoiceBus.speaker = oldSpeaker
            VoiceBus.onIdle = oldIdle
            VoiceBus.onSpeechIdle()
            VoiceBus.reset()
        }
    }
}
