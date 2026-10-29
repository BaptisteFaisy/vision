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
 * Mot du guidage : clip local sans synthèse quand le pack l'a, sinon synthèse
 * réseau mise en cache pour la suite. Le même mot ne se rejoue pas.
 */
class ProximityVoiceWordTest {

    private fun tempDir(): File = Files.createTempDirectory("mots").toFile()

    private fun voice(
        clips: VoicePack?,
        attempts: MutableList<String>,
        spoken: MutableList<String>,
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
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
    fun `meme mot deux fois, parle une fois`() = runTest {
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

        assertEquals(1, spoken.count { it == "Gauche." })
    }

    @Test
    fun `urgence externe coupe le chat en clip`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val pack = VoicePack(
            filesDir = tempDir(),
            packVoiceId = { "voix-test" },
            bundledClip = { "faux-mp3".toByteArray() },
        )

        voice(pack, attempts, spoken).reportEmergency(SpeechLanguage.Fr, playerIdle = false)

        assertTrue(attempts.isEmpty())
        assertEquals("Stop.", spoken.first())
    }
}
