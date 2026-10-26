package dev.sam3vision.app.vm

import dev.sam3vision.app.SPEAK_NO_KEY
import dev.sam3vision.app.SPEAK_TEXT_MISSING
import dev.sam3vision.app.SPEAK_TIMEOUT
import dev.sam3vision.app.SPEAK_TIMEOUT_MS
import dev.sam3vision.app.SpeakState
import dev.sam3vision.app.UiState
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La voix : les refus partent avant tout réseau, une synthèse en cours peut être coupée
 * (état inactif, aucune lecture), et chaque échec — synthèse comme lecture — laisse un
 * motif affichable. Aucun `MediaPlayer` ici : [AppVoice] ne prend que des fonctions.
 */
class AppVoiceTest {

    private val screen = FakeScreen()
    private val played = mutableListOf<ByteArray>()
    private var stopCalls = 0
    private var synthCalls = 0

    private fun voice(
        scope: CoroutineScope,
        settings: Settings = ConfiguredSettings,
        timeoutMs: Long = SPEAK_TIMEOUT_MS,
        synthesize: suspend (String) -> Result<ByteArray> = { Result.success(byteArrayOf(1, 2)) },
        play: suspend (ByteArray) -> Result<Unit> = { played += it; Result.success(Unit) },
    ): AppVoice = AppVoice(
        settings = { settings },
        update = screen.update,
        scope = scope,
        play = play,
        stopPlayback = { stopCalls++ },
        timeoutMs = timeoutMs,
        synthesize = { _, phrase -> synthCalls++; synthesize(phrase) },
    )

    @Test
    fun `cle TTS absente — refus local, aucun appel reseau`() = runTest {
        val voice = voice(this, settings = Settings.Default)

        voice.speak("  bonjour  ")

        assertEquals(SpeakState.Error("bonjour", SPEAK_NO_KEY), screen.value.speak)
        assertEquals(0, synthCalls)
        assertTrue("rien n'a été lu", played.isEmpty())
        assertEquals("la lecture en cours est coupée quand même", 1, stopCalls)
    }

    @Test
    fun `texte vide — refus local`() = runTest {
        voice(this).speak("   ")

        assertEquals(SpeakState.Error("", SPEAK_TEXT_MISSING), screen.value.speak)
        assertEquals(0, synthCalls)
    }

    @Test
    fun `synthese reussie — etat lecture puis termine`() = runTest {
        val voice = voice(this)

        voice.speak("salut")
        testScheduler.advanceUntilIdle()
        assertEquals(SpeakState.Playing("salut"), screen.value.speak)
        assertEquals(1, played.size)

        voice.onFinished()
        assertEquals(SpeakState.Done("salut"), screen.value.speak)
    }

    @Test
    fun `annulation — stop coupe la synthese en cours`() = runTest {
        val voice = voice(this, synthesize = { delay(10_000); Result.success(byteArrayOf(1)) })

        voice.speak("salut")
        testScheduler.runCurrent()
        assertEquals(SpeakState.Synthesizing("salut"), screen.value.speak)

        voice.stop()
        assertEquals(SpeakState.Idle, screen.value.speak)

        testScheduler.advanceUntilIdle()
        assertTrue("la phrase coupée n'est jamais lue", played.isEmpty())
    }

    @Test
    fun `echec de synthese — motif affiche`() = runTest {
        val voice = voice(this, synthesize = { Result.failure(ApiError.PaymentRequired("plus de crédits")) })

        voice.speak("salut")
        testScheduler.advanceUntilIdle()

        assertEquals(SpeakState.Error("salut", "plus de crédits"), screen.value.speak)
    }

    @Test
    fun `synthese trop longue — delai depasse, jamais d etat en cours sans fin`() = runTest {
        val voice = voice(this, timeoutMs = 100, synthesize = { delay(10_000); Result.success(ByteArray(1)) })

        voice.speak("salut")
        testScheduler.advanceUntilIdle()

        assertEquals(SpeakState.Error("salut", SPEAK_TIMEOUT), screen.value.speak)
    }

    @Test
    fun `lecture impossible — motif lisible`() = runTest {
        val voice = voice(this, play = { Result.failure(IllegalStateException("mp")) })

        voice.speak("salut")
        testScheduler.advanceUntilIdle()

        assertEquals(SpeakState.Error("salut", "lecture impossible : mp"), screen.value.speak)
        assertTrue(played.isEmpty())
    }
}