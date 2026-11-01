package dev.sam3vision.core.crossing

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraversePhase
import dev.sam3vision.core.traverse.TraverseVeto
import dev.sam3vision.core.traverse.crossingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table de décision du mode Traversée : le feu **et** l'état des véhicules
 * décident, en anglais mot pour mot (« You can cross » / « Don't cross yet »).
 *
 * Un véhicule en mouvement (ou proche non prouvé immobile) force l'attente
 * même sur vert ; un véhicule prouvé immobile (mesuré ≤ 15 m, centre quasi
 * fixe, seau stable, boîte qui ne grandit pas, fond fixe) ne bloque plus. Le
 * rouge interdit toujours (état binaire, fini le doute).
 *
 * Sortie : 4 frames publiées sans passage ni feu, ou 4 s d'horloge sans
 * scène (le réseau figé ne coince plus le mode).
 *
 * Déclenchement : celui de [CrossingOffer.seesNearCrosswalk] (passage proche,
 * voir [CrossingOfferTest]), inchangé ici.
 */
class CrossingTriggerExitDecisionTest {

    private val focalPx = 1000f

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, width = 640, height = 480)

    /** Voiture à 2,9 m (500 px de haut à focale 1000), centrée : arrêt mesurable. */
    private fun stoppedBox(): List<Float> = listOf(70f, 0f, 570f, 500f)

    @Test
    fun `vert et vehicule en mouvement - Don't cross yet`() {
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 60f, 50f))), focalPx, 0L)
        val decision = veto.update(true, listOf(detection("car", 0.9f, listOf(0f, 0f, 80f, 60f))), focalPx, 500L)

        assertTrue(decision.veto)
        assertEquals(TraversePhase.ALERTE, decision.state)
        assertEquals(CrossingState.Red, decision.crossingState())
        assertEquals("Don't cross yet", CrossingText.phrase(decision.crossingState(), SpeechLanguage.En))
    }

    @Test
    fun `vert et vehicule confirme a l arret - You can cross`() {
        val veto = TraverseVeto()
        veto.update(true, listOf(detection("car", 0.9f, stoppedBox())), focalPx, 0L)
        val confirmed = veto.update(true, listOf(detection("car", 0.9f, stoppedBox())), focalPx, 500L)

        assertFalse("arrêt confirmé : pas de veto", confirmed.veto)
        assertEquals(TraversePhase.PRET, confirmed.state)
        val decision = veto.update(true, listOf(detection("car", 0.9f, stoppedBox())), focalPx, 1000L)

        assertFalse(decision.veto)
        assertEquals("jamais de veto : le vert avance", TraversePhase.TRAVERSER, decision.state)
        assertEquals(CrossingState.Green, decision.crossingState())
        assertEquals("You can cross", CrossingText.phrase(decision.crossingState(), SpeechLanguage.En))
    }

    @Test
    fun `rouge et vehicule a l arret - Don't cross yet`() {
        val veto = TraverseVeto()
        veto.update(false, listOf(detection("car", 0.9f, stoppedBox())), focalPx, 0L)
        val decision = veto.update(false, listOf(detection("car", 0.9f, stoppedBox())), focalPx, 500L)

        assertFalse(decision.veto)
        assertEquals(TraversePhase.ATTENDRE, decision.state)
        assertEquals("Don't cross yet", CrossingText.phrase(decision.crossingState(), SpeechLanguage.En))
    }

    @Test
    fun `phrases anglaises exactes de la decision`() {
        assertEquals("You can cross", CrossingText.phrase(CrossingState.Green, SpeechLanguage.En))
        assertEquals("Don't cross yet", CrossingText.phrase(CrossingState.Red, SpeechLanguage.En))
    }

    @Test
    fun `scene vide plus de 4 s - sortie meme sans 4 frames`() {
        val gate = CrossingLossGate()
        gate.noteFrame(visible = false, nowMs = 0L)
        assertTrue(gate.onFrame(false))
        assertFalse("horloge seule, 3,9 s : tient", gate.expired(3_900L))
        assertTrue("sortie par l'horloge", gate.expired(4_000L))
    }

    @Test
    fun `scene revue - l horloge repart de zero`() {
        val gate = CrossingLossGate()
        gate.noteFrame(visible = false, nowMs = 0L)
        assertTrue(gate.onFrame(false))
        gate.noteFrame(visible = true, nowMs = 3_000L)
        assertTrue(gate.onFrame(true))
        assertFalse("revue à 3 s : pas d'expiration à 6 s", gate.expired(6_000L))
        assertTrue(gate.expired(7_000L))
    }

    @Test
    fun `jamais nourrie - l horloge seule ne sort pas`() {
        assertFalse(CrossingLossGate().expired(1_000_000L))
    }
}
