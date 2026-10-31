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
 * File de rattrapage des phrases douces : tue par le chat ou la route, la
 * phrase en file est redite au silence, une fois, dans les 3 s — sans file,
 * elle est perdue comme avant.
 */
class ProximityVoiceQueueTest {

    private fun tempDir(): File = Files.createTempDirectory("file").toFile()

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
    fun `doux en file rejoue au silence, une seule synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention au poteau."), spoken)
        assertEquals(listOf("Attention au poteau."), attempts)
    }

    @Test
    fun `sans file, doux tue = perdu`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false)
        voice.flushPending(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `file perimee = perdue`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var now = 0L
        val voice = voice(attempts, spoken, nowMs = { now })

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        now = ProximityVoice.PENDING_TTL_MS + 1
        voice.flushPending(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `file tenue pendant p1p2 puis redite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.flushPending(playerIdle = true, busy = { true })
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Attention au poteau."), spoken)
    }

    @Test
    fun `file garde la plus fraiche`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention à la barrière.", playerIdle = false, queue = true)
        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention au poteau."), spoken)
        assertEquals(listOf("Attention au poteau."), attempts)
    }

    @Test
    fun `route tue, file puis redite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var route = true
        val voice = voice(attempts, spoken, routeSpeaking = { route })

        voice.onPhrase("Attention au poteau.", playerIdle = true, queue = true)
        assertTrue(spoken.isEmpty())

        route = false
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention au poteau."), spoken)
    }

    @Test
    fun `p1p2 en cours, phrase en file puis redite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = true, queue = true, busy = { true })
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true, busy = { false })

        assertEquals(listOf("Attention au poteau."), spoken)
    }

    @Test
    fun `reset vide la file`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken)

        voice.onPhrase("Attention au poteau.", playerIdle = false, queue = true)
        voice.reset()
        voice.flushPending(playerIdle = true)

        assertTrue(spoken.isEmpty())
        assertTrue(attempts.isEmpty())
    }
}
