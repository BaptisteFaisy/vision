package dev.sam3vision.core.traverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto sans feu : l'absence de feu confirmée par l'appelant vaut vert pour
 * la phase, mais le veto reste armé — sans feu, ce sont les véhicules qui
 * tranchent (le « vert gagne » du 02/11 ne vaut que pour un vrai vert).
 */
class TraverseVetoNoLightTest {

    private val focalPx = 1000f

    @Test
    fun `sans feu sans vehicule - traverse en deux frames`() {
        val veto = TraverseVeto()
        val first = veto.update(lightGreen = false, detections = emptyList(), focalPx = focalPx, noLight = true)
        assertEquals(TraversePhase.PRET, first.state)
        assertFalse(first.veto)
        val second = veto.update(lightGreen = false, detections = emptyList(), focalPx = focalPx, noLight = true)
        assertEquals(TraversePhase.TRAVERSER, second.state)
        assertEquals("cross", second.cue)
        assertFalse(second.veto)
    }

    @Test
    fun `sans feu et voiture en mouvement - veto, pas de vert gagne`() {
        val veto = TraverseVeto()
        veto.update(false, listOf(vehicleDet("car", listOf(0f, 0f, 60f, 50f))), focalPx, noLight = true)
        val second = veto.update(false, listOf(vehicleDet("car", listOf(0f, 0f, 200f, 200f))), focalPx, noLight = true)

        assertTrue("sans feu + approche : veto", second.veto)
        assertEquals("car", second.blockingLabel)
        assertEquals("wait", second.cue)
    }
}
