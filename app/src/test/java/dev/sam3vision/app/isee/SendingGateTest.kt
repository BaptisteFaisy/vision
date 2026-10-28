package dev.sam3vision.app.isee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte d'envoi vers SAM : la caméra active allume la carte, et sans caméra ni
 * enregistrement ni Traversée, rien ne part (la carte reste éteinte, zéro conso).
 */
class SendingGateTest {

    @Test
    fun `camera active envoie meme sans enregistrement ni traversee`() {
        assertTrue(sendingToSam(cameraActive = true, recordingVideo = false, crossingActive = false))
    }

    @Test
    fun `enregistrement seul envoie`() {
        assertTrue(sendingToSam(cameraActive = false, recordingVideo = true, crossingActive = false))
    }

    @Test
    fun `traversee seule envoie`() {
        assertTrue(sendingToSam(cameraActive = false, recordingVideo = false, crossingActive = true))
    }

    @Test
    fun `rien d_actif rien ne part`() {
        assertFalse(sendingToSam(cameraActive = false, recordingVideo = false, crossingActive = false))
    }
}
