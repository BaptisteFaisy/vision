package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.VOICE_SLICE_MS
import dev.sam3vision.app.sliceRateFor
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.crossing.CrossingLevels
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tranche voix 700 ms : un tick, une phrase — la vitesse monte (pitch
 * conservé, plafond ×3) pour tenir la durée réelle dans la tranche, jamais
 * sous la vitesse du réglage. Verdicts Traversée exceptés : dits entiers
 * (sécurité : « Don't cross » ne s'accélère ni ne se coupe jamais).
 */
class VoiceSliceTest {

    @Test
    fun `tranche 700 ms, plafond x3`() {
        assertEquals(700L, VOICE_SLICE_MS)
        assertEquals(3f, dev.sam3vision.app.MAX_SLICE_RATE)
    }

    @Test
    fun `pastille trottoir 500 ms, verdict entier, reste 700 ms`() {
        assertEquals(500L, dev.sam3vision.app.SIDEWALK_SLICE_MS)
        assertEquals(null, sliceMsForLevel(CrossingLevels.VERDICT))
        assertEquals(500L, sliceMsForLevel(OutdoorLevel.SIDEWALK))
        assertEquals(700L, sliceMsForLevel(OutdoorLevel.HUMAN))
        assertEquals(700L, sliceMsForLevel(OutdoorLevel.STAIRS))
    }

    @Test
    fun `sous la tranche on garde la vitesse du reglage`() {
        assertEquals(1.9f, sliceRateFor(durationMs = 674L, sliceMs = 700L, baseSpeed = 1.9f))
        assertEquals(1.9f, sliceRateFor(durationMs = 0L, sliceMs = 700L, baseSpeed = 1.9f))
        assertEquals(1.9f, sliceRateFor(durationMs = -5L, sliceMs = 700L, baseSpeed = 1.9f))
    }

    @Test
    fun `au-dessus on accelere au ratio exact`() {
        // « Left. » (1 760 ms) à ×2,51 tient en 700 ms.
        assertEquals(1760f / 700f, sliceRateFor(durationMs = 1760L, sliceMs = 700L, baseSpeed = 1.9f), 0.001f)
        // Réglage lent : la tranche prime sur le réglage.
        assertEquals(1760f / 700f, sliceRateFor(durationMs = 1760L, sliceMs = 700L, baseSpeed = 1f), 0.001f)
    }

    @Test
    fun `plafond x3, le reliquat depasse`() {
        // 2 400 ms à ×3 = 800 ms effectifs : mieux que ×1,9, pas dans la tranche.
        assertEquals(3f, sliceRateFor(durationMs = 2_400L, sliceMs = 700L, baseSpeed = 1.9f))
    }

    @Test
    fun `bus tranche les clips, verdict dit entier`() = runTest {
        val sink = SliceSink()
        val speaker = BusSpeaker(
            scope = backgroundScope,
            player = SpeechPlayer(Files.createTempDirectory("tranche").toFile()),
            clips = IseeClipPlayer(
                clips = VoicePack(
                    filesDir = Files.createTempDirectory("tranche-pack").toFile(),
                    packVoiceId = { "voix-test" },
                    bundledClip = { "faux-mp3".toByteArray() },
                ),
                voiceId = { "voix-test" },
                sink = sink,
            ),
            proximity = ProximityVoice(
                player = SpeechPlayer(Files.createTempDirectory("tranche-vox").toFile()),
                settings = { dev.sam3vision.core.settings.Settings.Default },
            ),
        )

        speaker.say(OutdoorLevel.CAR_MOVING, "Car.", "car", emptyList())
        speaker.say(CrossingLevels.VERDICT, "Cross.", "verdict_cross", emptyList())
        runCurrent()

        assertEquals(listOf("vp_car_en"), sink.sliced)
        assertEquals(listOf("vp_verdict_cross_en"), sink.plain)
    }

    @Test
    fun `bus lit le trottoir en 500 ms, le reste en 700 ms`() = runTest {
        val sink = SliceSink()
        val speaker = BusSpeaker(
            scope = backgroundScope,
            player = SpeechPlayer(Files.createTempDirectory("tranche500").toFile()),
            clips = IseeClipPlayer(
                clips = VoicePack(
                    filesDir = Files.createTempDirectory("tranche500-pack").toFile(),
                    packVoiceId = { "voix-test" },
                    bundledClip = { "faux-mp3".toByteArray() },
                ),
                voiceId = { "voix-test" },
                sink = sink,
            ),
            proximity = ProximityVoice(
                player = SpeechPlayer(Files.createTempDirectory("tranche500-vox").toFile()),
                settings = { dev.sam3vision.core.settings.Settings.Default },
            ),
        )

        speaker.say(OutdoorLevel.SIDEWALK, "Car.", "car", emptyList())
        speaker.say(OutdoorLevel.HUMAN, "Car.", "car", emptyList())
        runCurrent()

        assertEquals(listOf(500L, 700L), sink.slicedMs)
    }

    /** Puits faux : distingue lecture simple et lecture en tranche (durées capturées). */
    private class SliceSink : ClipSink {
        val plain = mutableListOf<String>()
        val sliced = mutableListOf<String>()
        val slicedMs = mutableListOf<Long>()

        override suspend fun playFile(file: File): Result<Unit> {
            plain += file.nameWithoutExtension
            return Result.success(Unit)
        }

        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> {
            sliced += file.nameWithoutExtension
            slicedMs += sliceMs
            return Result.success(Unit)
        }

        override suspend fun playQueued(bytes: ByteArray) =
            Result.success(dev.sam3vision.app.PlaybackEnd.Done)

        override fun stop() = Unit
    }
}
