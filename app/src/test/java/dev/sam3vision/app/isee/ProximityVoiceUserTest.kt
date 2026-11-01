package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
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
 * La parole du user passe devant tout (ordre user du 1/11) : tant qu'il
 * tient le micro, aucune annonce de proximité ne parle — les phrases
 * attendent le silence et s'y redisent ; seule la voix de sécurité garde ses
 * règles. Le stop de Traversée, lui, n'attend jamais.
 */
class ProximityVoiceUserTest {

    private var speaking = false
    private var now = 0L
    private val spoken = mutableListOf<String>()

    private fun voice(): ProximityVoice {
        val dir = Files.createTempDirectory("voix-user").toFile()
        return ProximityVoice(
            player = SpeechPlayer(File(dir, "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
            onSpeaking = { spoken += it },
            synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
            userSpeaking = { speaking },
            nowMs = { now },
        )
    }

    /** Phrases réellement dites (le fanion rendu n'est pas une phrase). */
    private fun said(): List<String> = spoken.filter { it.isNotBlank() }

    @Test
    fun `user muet, annonce parlee`() = runTest {
        val voice = voice()

        voice.onPhrase("Attention à la barrière, à 2 heures.", playerIdle = true, queue = true)

        assertEquals(listOf("Attention à la barrière, à 2 heures."), said())
    }

    @Test
    fun `user qui parle, annonce gardee puis redite au silence`() = runTest {
        val voice = voice()
        speaking = true

        voice.onPhrase("Attention à la barrière, à 2 heures.", playerIdle = true, queue = true)
        assertTrue(said().isEmpty())
        // Fanion rendu : la lecture coupée ne reste pas crue occupée.
        assertTrue(spoken.contains(""))

        speaking = false
        now = 1_000L
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention à la barrière, à 2 heures."), said())
    }

    @Test
    fun `user qui parle, la securite ne coupe plus`() = runTest {
        val voice = voice()
        speaking = true

        voice.onPhrase("Attention à la personne, passez à gauche.", playerIdle = true, urgent = true, queue = true)

        assertTrue(said().isEmpty())

        speaking = false
        voice.flushPending(playerIdle = true)

        assertEquals(listOf("Attention à la personne, passez à gauche."), said())
    }

    @Test
    fun `stop de Traversee jamais retenu`() = runTest {
        val voice = voice()
        speaking = true

        voice.speakWord("guide_stop", "Stop.", SpeechLanguage.Fr, playerIdle = true, urgent = true)

        assertEquals(listOf("Stop."), said())
    }

    @Test
    fun `meme annonce pas redite dans la fenetre`() = runTest {
        val voice = voice()
        val phrase = "Attention à la barrière, à 2 heures."

        voice.onPhrase(phrase, playerIdle = true)
        now = 1_000L
        voice.onPhrase(phrase, playerIdle = true)
        assertEquals(1, said().size)

        // Après la fenêtre : elle se redit (le danger dure).
        now = 20_000L
        voice.onPhrase(phrase, playerIdle = true)
        assertEquals(2, said().size)
    }

    @Test
    fun `deux annonces differentes, une seule par silence`() = runTest {
        val voice = voice()

        voice.onPhrase("Attention à la barrière, à 2 heures.", playerIdle = true)
        now = 1_000L
        voice.onPhrase("Attention à la personne, à 9 heures, passez à gauche.", playerIdle = true)

        assertEquals(listOf("Attention à la barrière, à 2 heures."), said())
    }

    @Test
    fun `phrase sans attention non cadencee`() = runTest {
        val voice = voice()

        voice.onPhrase("Voiture en mouvement, à 5 mètres.", playerIdle = true)
        now = 100L
        voice.onPhrase("Voiture à l'arrêt, à 2 mètres.", playerIdle = true)

        assertEquals(2, said().size)
        assertFalse(said().isEmpty())
    }
}
