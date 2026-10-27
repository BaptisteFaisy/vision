package dev.sam3vision.app

import dev.sam3vision.core.net.ElevenLabsSttClient
import dev.sam3vision.core.net.FallbackSpeechClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde de l'ordre des moteurs : **Meta Muse d'abord**, ElevenLabs Scribe en repli.
 *
 * Le contrat du repli lui-même est testé dans `:core` (`FallbackSpeechClientTest`) ;
 * ici on verrouille seulement le choix fait par l'app, celui que partagent la dictée
 * du chat, le mode Direct et l'appui long d'iSee.
 */
class SpeechClientsTest {

    private val metaKey = "meta-dictee"
    private val elevenKey = "sk_eleven"

    @Test
    fun `cle Meta seule donne le client Muse`() {
        val client = speechClient(Settings.Default.copy(asrApiKey = metaKey))
        assertTrue("Muse doit être le moteur de la dictée", client is MetaAsrClient)
    }

    @Test
    fun `cle Meta plus voix donne la chaine Muse puis Scribe`() {
        val client = speechClient(Settings.Default.copy(asrApiKey = metaKey, ttsApiKey = elevenKey))
        assertTrue("Muse premier, Scribe en repli", client is FallbackSpeechClient)
    }

    @Test
    fun `sans cle Meta la dictee retombe sur Scribe`() {
        val client = speechClient(Settings.Default.copy(ttsApiKey = elevenKey))
        assertTrue(client is ElevenLabsSttClient)
    }

    @Test
    fun `sans aucune cle il n y a pas de moteur`() {
        assertNull(speechClient(Settings.Default))
    }
}