package dev.sam3vision.app

import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trace voix de l'annonceur : `EXPECT` à la demande, `FAIL` à l'échec — le
 * lecteur interne relaie ses `play`/`stop`/`done`/`cut` au même canal. Sans
 * réseau : le cache partagé est amorcé à la clé de la phrase, puis vidé.
 */
class VoiceAnnouncerOnEventTest {

    private val seededKeys = mutableListOf<String>()

    @After
    fun viderCache() {
        seededKeys.forEach { ElevenLabsTtsClient.sharedPhrases.remove(it) }
        seededKeys.clear()
    }

    private fun tempDir(): File = Files.createTempDirectory("parole-1b").toFile()

    private fun settings() = Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test")

    private fun voice(events: MutableList<String>) =
        VoiceAnnouncer(SpeechPlayer(tempDir(), onEvent = { events += it }), onEvent = { events += it })

    /** Amorce le cache partagé : la synthèse rejoue les octets sans réseau. */
    private fun seedCache(text: String) {
        val key = ElevenLabsTtsClient.phraseKey(
            ElevenLabsTtsClient.DEFAULT_TTS_BASE_URL,
            "voix-test",
            ElevenLabsTtsClient.DEFAULT_TTS_MODEL,
            text,
        )
        ElevenLabsTtsClient.putPhrase(ElevenLabsTtsClient.sharedPhrases, key, "faux-mp3".toByteArray())
        seededKeys += key
    }

    @Test
    fun `texte vide - succes silencieux, sans EXPECT`() = runTest {
        val events = mutableListOf<String>()

        val result = voice(events).say(settings(), "   ")

        assertTrue(result.isSuccess)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `cle absente - EXPECT puis FAIL`() = runTest {
        val events = mutableListOf<String>()

        val result = voice(events).say(settings().copy(ttsApiKey = "  "), "Bonjour.")

        assertTrue(result.isFailure)
        assertEquals(listOf("EXPECT", "FAIL"), events)
    }

    @Test
    fun `lecture impossible en JVM - EXPECT, stop, play, FAIL`() = runTest {
        val events = mutableListOf<String>()
        seedCache("Bonjour.")

        val result = voice(events).say(settings(), "Bonjour.")

        assertTrue(result.isFailure)
        assertEquals(listOf("EXPECT", "stop", "play", "FAIL"), events)
    }
}
