package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Redite bornée des phrases douces : un danger présent ne se tait pas
 * (esquive prioritaire, ordre user du 31/10) et le retour d'une scène
 * parle — mais jamais de bégaiement (une phrase identique attend le
 * délai avant de reparler, et le stop ne se répète jamais).
 */
class ProximityVoiceRepeatTest {

    private fun tempDir(): File = Files.createTempDirectory("file").toFile()

    private fun voice(
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        safetyDriver: SafetyTestDriver = SafetyTestDriver(),
        nowMs: () -> Long = { 0L },
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        safety = testSafetyVoice(safetyDriver),
        onSpeaking = { if (it.isNotEmpty()) spoken += it },
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        nowMs = nowMs,
    )

    @Test
    fun `doux identique redit apres le delai`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onPhrase("Voiture arrêtée.", playerIdle = true)
        now = REPEAT_GAP_MS
        voice.onPhrase("Voiture arrêtée.", playerIdle = true)

        assertEquals(listOf("Voiture arrêtée.", "Voiture arrêtée."), spoken)
        assertEquals(2, attempts.size)
    }

    @Test
    fun `doux identique tue avant le delai`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Voiture arrêtée.", playerIdle = true)
        voice.onPhrase("Voiture arrêtée.", playerIdle = true)

        assertEquals(listOf("Voiture arrêtée."), spoken)
        assertEquals(1, attempts.size)
    }

    @Test
    fun `urgent persistant redit apres le delai, en securite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val safetyDriver = SafetyTestDriver()
        var now = 0L
        val voice = voice(attempts, spoken, safetyDriver, nowMs = { now })

        voice.onPhrase("Voiture en mouvement.", playerIdle = true, urgent = true)
        now = REPEAT_GAP_MS
        voice.onPhrase("Voiture en mouvement.", playerIdle = true, urgent = true)

        assertEquals(listOf("Voiture en mouvement.", "Voiture en mouvement."), safetyDriver.calls.map { it.text })
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `urgent parle sans cle, hors-ligne`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val safetyDriver = SafetyTestDriver()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default },
            safety = testSafetyVoice(safetyDriver),
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.success("$text-audio".toByteArray())
            },
        )

        voice.onPhrase("Voiture en mouvement.", playerIdle = false, urgent = true)
        voice.onPhrase("Voiture arrêtée.", playerIdle = true)

        assertEquals(listOf("Voiture en mouvement."), safetyDriver.calls.map { it.text })
        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `route identique redite apres le delai`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onRoutePhrase("Tournez à gauche.", playerIdle = true)
        now = REPEAT_GAP_MS
        voice.onRoutePhrase("Tournez à gauche.", playerIdle = true)

        assertEquals(listOf("Tournez à gauche.", "Tournez à gauche."), spoken)
        assertEquals(2, attempts.size)
    }

    @Test
    fun `arrivee identique redite apres le delai`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onArrivalPhrase("Sur le passage.", playerIdle = true)
        now = REPEAT_GAP_MS
        voice.onArrivalPhrase("Sur le passage.", playerIdle = true)

        assertEquals(listOf("Sur le passage.", "Sur le passage."), spoken)
        assertEquals(2, attempts.size)
    }

    @Test
    fun `stop jamais repete meme apres le delai`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val safetyDriver = SafetyTestDriver()
        var now = 0L
        val voice = voice(attempts, spoken, safetyDriver, nowMs = { now })
        val stop = GuidanceWord.Stop
        val text = stop.text(SpeechLanguage.En)

        voice.speakWord(stop.clipId, text, SpeechLanguage.En, playerIdle = true, urgent = true)
        now = REPEAT_GAP_MS
        voice.speakWord(stop.clipId, text, SpeechLanguage.En, playerIdle = true, urgent = true)

        assertEquals(listOf(text), safetyDriver.calls.map { it.text })
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    companion object {
        /**
         * Délai de redite attendu (miroir de `ProximityVoice.REPEAT_TTL_MS`,
         * lisible avant comme après le fix : le test doit échouer sans lui).
         */
        const val REPEAT_GAP_MS = 4_000L
    }
}
