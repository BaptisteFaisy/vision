package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Refus de la dictée du direct : fonction pure, sans Android ni réseau. */
class LiveVoiceTest {

    private val withEngine = Settings.Default.copy(asrApiKey = "cle-de-test")

    @Test
    fun `micro refuse est dit clairement`() {
        assertEquals("Micro non autorisé", voiceBlocker(withEngine, micGranted = false, micBusy = false))
    }

    @Test
    fun `sans moteur de dictee on renvoie vers les reglages`() {
        val message = voiceBlocker(Settings.Default, micGranted = true, micBusy = false)
        assertEquals("aucun moteur de dictée : clé Meta ou ElevenLabs (Réglages)", message)
    }

    @Test
    fun `micro deja pris par une autre fonction`() {
        assertEquals("micro déjà occupé", voiceBlocker(withEngine, micGranted = true, micBusy = true))
    }

    @Test
    fun `tout est pret aucun refus`() {
        assertNull(voiceBlocker(withEngine, micGranted = true, micBusy = false))
    }
}
