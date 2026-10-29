package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage avec cap : couloir par direction, passage conseillé, phrases, signatures. */
class GuidanceSteeringTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    @Test
    fun `cap par defaut le droit, comportement V1 inchange`() {
        val labels = listOf(placed("personne", 0.5f, 2f))

        assertEquals(Guidance.decide(labels), Guidance.decide(labels, SteeringCap.Straight))
    }

    @Test
    fun `couloir lateral la moitie visee`() {
        val gauche = listOf(placed("personne", 0.2f, 1f))
        val droite = listOf(placed("personne", 0.8f, 1f))

        assertTrue(Guidance.decide(gauche, SteeringCap.Left).blocked)
        assertFalse(Guidance.decide(gauche, SteeringCap.Straight).blocked)
        assertTrue(Guidance.decide(droite, SteeringCap.Right).blocked)
        assertFalse(Guidance.decide(droite, SteeringCap.Straight).blocked)
    }

    @Test
    fun `objet pile au centre bloque les trois caps`() {
        val labels = listOf(placed("personne", 0.5f, 1f))

        assertTrue(Guidance.decide(labels, SteeringCap.Left).blocked)
        assertTrue(Guidance.decide(labels, SteeringCap.Straight).blocked)
        assertTrue(Guidance.decide(labels, SteeringCap.Right).blocked)
    }

    @Test
    fun `objet lointain ne bloque aucun cap`() {
        val labels = listOf(placed("personne", 0.2f, 10f))

        assertFalse(Guidance.decide(labels, SteeringCap.Left).blocked)
    }

    @Test
    fun `passage le secteur le moins encombre`() {
        val labels = listOf(
            placed("personne", 0.2f, 2f),
            placed("chaise", 0.5f, 1f),
        )

        val decision = Guidance.decide(labels, SteeringCap.Left)

        assertTrue(decision.blocked)
        assertEquals(SteeringCap.Right, decision.passage)
    }

    @Test
    fun `passage le centre en premier a egalite`() {
        val bloqueGauche = listOf(placed("personne", 0.2f, 2f))
        val bloqueDroite = listOf(placed("personne", 0.8f, 2f))

        assertEquals(SteeringCap.Straight, Guidance.decide(bloqueGauche, SteeringCap.Left).passage)
        assertEquals(SteeringCap.Straight, Guidance.decide(bloqueDroite, SteeringCap.Right).passage)
    }

    @Test
    fun `passage le plus loin a comptes egaux`() {
        val labels = listOf(
            placed("personne", 0.2f, 2f),
            placed("chaise", 0.5f, 1f),
            placed("table", 0.8f, 3f),
        )

        assertEquals(SteeringCap.Right, Guidance.decide(labels, SteeringCap.Left).passage)
    }

    @Test
    fun `cap droit le passage reprend le cote V1`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertEquals(GuidanceSide.Left, decision.side)
        assertEquals(SteeringCap.Left, decision.passage)
    }

    @Test
    fun `cap lateral pas de cote V1`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.2f, 2f)), SteeringCap.Left)

        assertNull(decision.side)
        assertEquals(SteeringCap.Straight, decision.passage)
    }

    @Test
    fun `mot voie libre par cap`() {
        assertEquals("Gauche.", Guidance.phrase(Guidance.decide(emptyList(), SteeringCap.Left)))
        assertEquals("Droite.", Guidance.phrase(Guidance.decide(emptyList(), SteeringCap.Right)))
        assertEquals(
            "Left.",
            Guidance.phrase(Guidance.decide(emptyList(), SteeringCap.Left), SpeechLanguage.En),
        )
    }

    @Test
    fun `mot cap bouche avec passage`() {
        val gauche = Guidance.decide(listOf(placed("personne", 0.2f, 2f)), SteeringCap.Left)

        assertEquals("Tout droit.", Guidance.phrase(gauche))
    }

    @Test
    fun `mot cap bouche anglais et sans distance`() {
        val droite = Guidance.decide(listOf(placed("person", 0.8f, 2f)), SteeringCap.Right)

        assertEquals("Straight.", Guidance.phrase(droite, SpeechLanguage.En))
        val sansDistance = Guidance.decide(listOf(placed("personne", 0.2f)), SteeringCap.Left)

        assertEquals("Tout droit.", Guidance.phrase(sansDistance))
    }

    @Test
    fun `signature le mot seul`() {
        assertEquals("w|left", Guidance.key(Guidance.decide(emptyList(), SteeringCap.Left)))

        val bloque = Guidance.decide(listOf(placed("personne", 0.2f, 2.4f)), SteeringCap.Left)

        assertEquals("w|straight", Guidance.key(bloque))
    }
}
