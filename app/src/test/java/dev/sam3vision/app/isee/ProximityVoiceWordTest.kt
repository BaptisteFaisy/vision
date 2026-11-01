package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mot du guidage (SPEC v4) : clip local quand le pack l'a (zéro réseau),
 * sinon synthèse GPS mise en cache (hors GPS : silence) ; directions
 * redites en continu, stop unique ; gardes user, porte et système (niveau
 * 0 excepté).
 */
class ProximityVoiceWordTest {

    private fun tempDir(): File = Files.createTempDirectory("mots").toFile()

    private fun packAvecClip(): VoicePack = VoicePack(
        filesDir = tempDir(),
        packVoiceId = { "voix-test" },
        bundledClip = { "faux-mp3".toByteArray() },
    )

    private fun packVide(): VoicePack = VoicePack(
        filesDir = tempDir(),
        packVoiceId = { "voix-test" },
        bundledClip = { null },
    )

    private fun voice(
        clips: VoicePack?,
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        canPlay: () -> Boolean = { true },
        systemBusy: () -> Boolean = { false },
        userSpeaking: () -> Boolean = { false },
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        onSpeaking = { spoken += it },
        canPlay = canPlay,
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        clips = clips,
        systemBusy = systemBusy,
        userSpeaking = userSpeaking,
    )

    @Test
    fun `clip present, aucune synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(packAvecClip(), attempts, spoken)
            .speakWord("guide_left", "Gauche.", SpeechLanguage.Fr)

        assertTrue(dit)
        assertTrue(attempts.isEmpty())
        assertEquals("Gauche.", spoken.first())
    }

    @Test
    fun `clip absent niveau GPS, synthese mise en cache`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(packVide(), attempts, spoken)

        assertTrue(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))
        assertEquals(listOf("Gauche."), attempts)

        voice.reset()
        assertTrue(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))
        assertEquals(listOf("Gauche."), attempts)
    }

    @Test
    fun `clip absent hors GPS, silence sans synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(packVide(), attempts, spoken)
            .speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, level = OutdoorLevel.HUMAN)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `meme mot doux deux fois, parle deux fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(packAvecClip(), attempts, spoken)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr)
        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr)

        assertEquals(2, spoken.count { it == "Gauche." })
    }

    @Test
    fun `meme stop deux fois, dit une fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(packAvecClip(), attempts, spoken)

        assertTrue(voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr))
        assertFalse(voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr))

        assertEquals(1, spoken.count { it == "Stop." })
        assertTrue(attempts.isEmpty())
    }

    @Test
    fun `porte fermee, jete sans synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(packAvecClip(), attempts, spoken, canPlay = { false })
            .speakWord("guide_left", "Gauche.", SpeechLanguage.Fr)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `niveau 0 passe la porte fermee`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(packAvecClip(), attempts, spoken, canPlay = { false })
            .speakWord("car", "Voiture.", SpeechLanguage.Fr, level = OutdoorLevel.CAR_MOVING)

        assertTrue(dit)
        assertEquals("Voiture.", spoken.first())
    }

    @Test
    fun `user qui parle, mot jete et lecture coupee`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(packAvecClip(), attempts, spoken, userSpeaking = { true })

        assertFalse(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr))
        assertFalse(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr))

        assertTrue(attempts.isEmpty())
        assertTrue(spoken.all { it.isEmpty() })
    }

    @Test
    fun `systeme occupe, mot jete`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(packAvecClip(), attempts, spoken, systemBusy = { true })
            .speakWord("guide_left", "Gauche.", SpeechLanguage.Fr)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }
}
