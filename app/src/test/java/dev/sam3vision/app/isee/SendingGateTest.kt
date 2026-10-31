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

/**
 * Porte picto : les repères armés sont cherchés dès que la caméra montre
 * quelque chose (page blanche) ou que la vidéo tourne — sinon rien ne part.
 */
class PicturingGateTest {

    @Test
    fun `camera active cherche sans enregistrement`() {
        assertTrue(picturingToMatcher(armed = true, cameraActive = true, recordingVideo = false))
    }

    @Test
    fun `enregistrement seul cherche`() {
        assertTrue(picturingToMatcher(armed = true, cameraActive = false, recordingVideo = true))
    }

    @Test
    fun `sans repere arme rien ne part`() {
        assertFalse(picturingToMatcher(armed = false, cameraActive = true, recordingVideo = true))
    }

    @Test
    fun `sans camera ni enregistrement rien ne part`() {
        assertFalse(picturingToMatcher(armed = true, cameraActive = false, recordingVideo = false))
    }
}
