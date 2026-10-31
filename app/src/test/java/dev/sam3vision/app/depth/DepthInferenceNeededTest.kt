package dev.sam3vision.app.depth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L'inférence embarquée (~176 ms DA2) ne tourne que quand la profondeur
 * serveur manque : iSee reçoit des médianes serveur à chaque réponse et
 * [LiveDepthMeter.readingsFor] les préfère — inférer en plus saturerait
 * ~1 cœur pour rien. Repli préservé : démarrage et silence serveur.
 */
class DepthInferenceNeededTest {

    @Test
    fun `demarrage sans reponse serveur, on infere`() {
        assertTrue(depthInferenceNeeded(nowMs = 10_000L, lastServerDepthMs = 0L))
    }

    @Test
    fun `serveur frais, on jette la frame`() {
        assertFalse(depthInferenceNeeded(nowMs = 10_000L, lastServerDepthMs = 9_900L))
        assertFalse(depthInferenceNeeded(nowMs = 10_000L, lastServerDepthMs = 8_501L))
    }

    @Test
    fun `silence serveur, l'embarque reprend`() {
        assertTrue(depthInferenceNeeded(nowMs = 10_000L, lastServerDepthMs = 8_500L))
        assertTrue(depthInferenceNeeded(nowMs = 20_000L, lastServerDepthMs = 10_000L))
    }
}
