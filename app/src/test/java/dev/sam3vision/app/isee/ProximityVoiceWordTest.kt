package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mot du guidage : doux en clip local sans synthèse quand le pack l'a, sinon
 * synthèse réseau mise en cache pour la suite. Directions douces redites en
 * continu, stop unique — et l'urgent part en voix de sécurité, jamais en clip.
 */
class ProximityVoiceWordTest {

    private fun tempDir(): File = Files.createTempDirectory("mots").toFile()

    private fun voice(
        clips: VoicePack?,
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        safetyDriver: SafetyTestDriver = SafetyTestDriver(),
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        safety = testSafetyVoice(safetyDriver),
        onSpeaking = { spoken += it },
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        clips = clips,
    )

    @Test
    fun `clip present, aucune synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )

        voice(pack, attempts, spoken).speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)

        assertTrue(attempts.isEmpty())
        assertEquals("Gauche.", spoken.first())
    }

    @Test
    fun `clip absent, synthese mise en cache`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { null },
        )
        val voice = voice(pack, attempts, spoken)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)
        assertEquals(listOf("Gauche."), attempts)

        voice.reset()
        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)
        assertEquals(listOf("Gauche."), attempts)
    }

    @Test
    fun `meme mot doux deux fois, parle deux fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = voice(pack, attempts, spoken)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)
        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)

        assertEquals(2, spoken.count { it == "Gauche." })
    }

    @Test
    fun `meme stop deux fois, securite une fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val safetyDriver = SafetyTestDriver()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = voice(pack, attempts, spoken, safetyDriver)

        voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr, playerIdle = true, urgent = true)
        voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr, playerIdle = true, urgent = true)

        assertEquals(listOf("Stop."), safetyDriver.calls.map { it.text })
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `mot doux avale puis reemis, parle`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = voice(pack, attempts, spoken)

        // Avalé par la route (voie occupée) : rien dit, rien mémorisé.
        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = false, urgent = false)
        assertTrue(spoken.isEmpty())

        // Réémis au silence : parlé (pas d'empoisonnement de la dédup).
        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true, urgent = false)

        assertEquals("Gauche.", spoken.first())
    }

    @Test
    fun `mot doux en file rejoue au silence`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = voice(pack, attempts, spoken)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = false, urgent = false, queue = true)
        assertTrue(spoken.isEmpty())

        voice.flushPending(playerIdle = true)

        assertEquals("Gauche.", spoken.first())
    }

    @Test
    fun `mot doux avale par le chat se redit ensuite`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = voice(pack, attempts, spoken)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = false, urgent = false)
        assertTrue(spoken.isEmpty())

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true, urgent = false)

        assertEquals("Gauche.", spoken.first())
    }
}
