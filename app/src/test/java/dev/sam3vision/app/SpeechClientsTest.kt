package dev.sam3vision.app

import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.ElevenLabsSttClient
import dev.sam3vision.core.net.FallbackSpeechClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Garde de l'ordre des moteurs : **Meta Muse d'abord**, ElevenLabs Scribe en repli.
 *
 * Le contrat du repli lui-même est testé dans `:core` (`FallbackSpeechClientTest`) ;
 * ici on verrouille seulement le choix fait par l'app, celui que partagent la dictée
 * du chat, le mode Direct et l'appui long d'iSee.
 *
 * Les deux derniers tests couvrent la capacité **synthèse** ([ttsClient]) : elle
 * reprend la clé et la voix des réglages, et laisse le client de `:core` refuser les
 * cas invalides — aucun appel réseau n'est fait, donc le test tourne hors ligne.
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

    @Test
    fun `la synthese sans cle est refusee par le client, sans reseau`() = runBlocking {
        val error = ttsClient(Settings.Default).synthesize("Bonjour").exceptionOrNull()

        assertTrue("clé absente : refus local du client", error is ApiError.InvalidKey)
        assertTrue("le message doit dire quoi renseigner", error?.message.orEmpty().contains("Settings"))
    }

    @Test
    fun `la synthese reprend la voix des reglages et refuse une voix absente`() = runBlocking {
        val settings = Settings.Default.copy(ttsApiKey = elevenKey, ttsVoiceId = "   ")
        val error = ttsClient(settings).synthesize("Bonjour").exceptionOrNull()

        assertTrue("voix absente : refus local du client", error is ApiError.BadResponse)
        assertTrue("le refus doit nommer la voix", error?.message.orEmpty().contains("voice"))
    }
}
