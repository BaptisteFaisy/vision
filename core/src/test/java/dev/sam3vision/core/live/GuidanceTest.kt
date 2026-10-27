package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage « tout droit » : couloir central, côté de sortie, phrases FR/EN, signature. */
class GuidanceTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    @Test
    fun `voie libre sans objet`() {
        val decision = Guidance.decide(emptyList())

        assertFalse(decision.blocked)
        assertEquals("Continue tout droit.", Guidance.phrase(decision))
        assertEquals("Continue straight ahead.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `objets lateraux ne bloquent pas`() {
        val labels = listOf(placed("personne", 0.1f, 1f), placed("chien", 0.9f, 1f))

        assertFalse(Guidance.decide(labels).blocked)
    }

    @Test
    fun `obstacle au centre bloque vers le cote le plus libre`() {
        val labels = listOf(
            placed("personne", 0.5f, 2f),
            placed("chaise", 0.1f, 1f),
        )

        val decision = Guidance.decide(labels)

        assertTrue(decision.blocked)
        assertEquals(GuidanceSide.Right, decision.side)
        assertEquals("Personne à 2 mètres à midi, va à droite.", Guidance.phrase(decision))
    }

    @Test
    fun `distance inconnue bloque par prudence sans chiffre`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Personne à midi, va à gauche.", Guidance.phrase(decision))
        assertEquals("Personne at 12 o'clock, go left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `objet lointain au centre ne bloque pas`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 10f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `phrase anglaise avec distance`() {
        val decision = Guidance.decide(listOf(placed("person", 0.5f, 2f)))

        assertEquals("Person 2 meters at 12 o'clock, go left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `egalite des cotes on passe a l oppose du decalage`() {
        val decaleAGauche = Guidance.decide(listOf(placed("personne", 0.4f, 2f)))
        val decaleADroite = Guidance.decide(listOf(placed("personne", 0.6f, 2f)))

        assertEquals(GuidanceSide.Right, decaleAGauche.side)
        assertEquals(GuidanceSide.Left, decaleADroite.side)
    }

    @Test
    fun `labels vides ignores`() {
        val decision = Guidance.decide(listOf(placed("  ", 0.5f, 1f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `signature libre et signature obstacle`() {
        assertEquals("free", Guidance.key(Guidance.decide(emptyList())))

        val bloque = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))

        assertEquals("b|personne|Left|2|12h", Guidance.key(bloque))
    }

    @Test
    fun `la signature bouge au metre pres comme les positions`() {
        val deux = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))
        val trois = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))
        val deuxBis = Guidance.decide(listOf(placed("personne", 0.5f, 2.1f)))

        assertNotEquals(Guidance.key(deux), Guidance.key(trois))
        assertEquals(Guidance.key(deux), Guidance.key(deuxBis))
    }

    @Test
    fun `la signature bouge a l heure pres dans le couloir`() {
        val onze = Guidance.decide(listOf(placed("personne", 0.36f, 2f)))
        val midi = Guidance.decide(listOf(placed("personne", 0.44f, 2f)))

        assertEquals("b|personne|Right|2|11h", Guidance.key(onze))
        assertEquals("b|personne|Right|2|12h", Guidance.key(midi))
        assertEquals("Personne à 2 mètres à 11 heures, va à droite.", Guidance.phrase(onze))
    }
}
