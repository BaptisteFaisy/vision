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
 * File des consignes de guidage GPS : la douce tuée par une voix en cours
 * attend au lieu de mourir, et se redit au silence avant toute douce
 * d'obstacle (danger > route > doux) — une seule synthèse, jamais de rafale.
 */
class ProximityVoiceRouteTest {

    private fun tempDir(): File = Files.createTempDirectory("fileroute").toFile()

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
    fun `route douce tuee puis redite au silence, une seule synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onRoutePhrase("Tournez à gauche.", playerIdle = false)
        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())

        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Tournez à gauche."), spoken)
        assertEquals(listOf("Tournez à gauche."), attempts)
    }

    @Test
    fun `route prioritaire sur doux, un par un`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.onRoutePhrase("Tournez à gauche.", playerIdle = false)

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Tournez à gauche."), spoken)

        voice.flushPending(playerIdle = true)
        assertEquals(listOf("Tournez à gauche.", "Attention au poteau."), spoken)
    }

    @Test
    fun `route urgente coupe et parle aussitot, en securite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val safetyDriver = SafetyTestDriver()
        val voice = voice(attempts, spoken, safetyDriver)

        voice.onRoutePhrase("Écart de route.", playerIdle = false, urgent = true)

        assertEquals(listOf("Écart de route."), safetyDriver.calls.map { it.text })
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `route meme phrase, une seule fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onRoutePhrase("Tournez à gauche.", playerIdle = true)
        voice.onRoutePhrase("Tournez à gauche.", playerIdle = true)

        assertEquals(listOf("Tournez à gauche."), spoken)
    }

    @Test
    fun `route file perimee = perdue`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onRoutePhrase("Tournez à gauche.", playerIdle = false)
        now = ProximityVoice.PENDING_TTL_MS + 1
        voice.flushPending(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `reset vide la file route`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onRoutePhrase("Tournez à gauche.", playerIdle = false)
        voice.reset()
        voice.flushPending(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }
}
