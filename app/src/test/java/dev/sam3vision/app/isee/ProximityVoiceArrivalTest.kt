package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * File de l'arrivée sur le passage (« Sur le passage. ») : la plus basse
 * priorité de toutes les annonces (ordre user du 31/10) — parlée au silence
 * seul, sinon gardée et redite en dernier (danger > route > doux > arrivée),
 * jamais en coupure.
 */
class ProximityVoiceArrivalTest {

    private fun tempDir(): File = Files.createTempDirectory("filearrivee").toFile()

    private fun voice(
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        routeSpeaking: () -> Boolean = { false },
        nowMs: () -> Long = { 0L },
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        safety = testSafetyVoice(),
        onSpeaking = { if (it.isNotEmpty()) spoken += it },
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        routeSpeaking = routeSpeaking,
        nowMs = nowMs,
    )

    @Test
    fun `arrivee parlee au silence, une seule synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onArrivalPhrase("Sur le passage.", playerIdle = true)

        assertEquals(listOf("Sur le passage."), spoken)
        assertEquals(listOf("Sur le passage."), attempts)
    }

    @Test
    fun `arrivee tuee puis redite en dernier, un par un`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.onRoutePhrase("Tournez à gauche.", playerIdle = false)
        voice.onArrivalPhrase("Sur le passage.", playerIdle = false)
        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Tournez à gauche."), spoken)

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Tournez à gauche.", "Attention au poteau."), spoken)

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Tournez à gauche.", "Attention au poteau.", "Sur le passage."), spoken)
    }

    @Test
    fun `flushArrival tait l arrivee quand doux ou route attend`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.onArrivalPhrase("Sur le passage.", playerIdle = false)
        voice.flushArrival(playerIdle = true)
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Attention au poteau."), spoken)

        voice.flushArrival(playerIdle = true)
        assertEquals(listOf("Attention au poteau.", "Sur le passage."), spoken)
        assertEquals(2, attempts.size)
    }

    @Test
    fun `route qui parle tait l arrivee puis la rend`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var route = true
        val voice = voice(attempts, spoken, routeSpeaking = { route })

        voice.onArrivalPhrase("Sur le passage.", playerIdle = true)
        assertTrue(spoken.isEmpty())

        route = false
        voice.flushArrival(playerIdle = true)

        assertEquals(listOf("Sur le passage."), spoken)
    }

    @Test
    fun `arrivee perimee = perdue`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onArrivalPhrase("Sur le passage.", playerIdle = false)
        now = ProximityVoice.PENDING_TTL_MS + 1
        voice.flushPending(playerIdle = true)
        voice.flushArrival(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `reset vide la file arrivee`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onArrivalPhrase("Sur le passage.", playerIdle = false)
        voice.reset()
        voice.flushPending(playerIdle = true)
        voice.flushArrival(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }
}
