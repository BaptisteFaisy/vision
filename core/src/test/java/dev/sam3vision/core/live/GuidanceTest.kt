package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals("Continue.", Guidance.phrase(decision))
        assertEquals("Continue.", Guidance.phrase(decision, SpeechLanguage.En))
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
        assertEquals("Va à droite, 2 mètres.", Guidance.phrase(decision))
    }

    @Test
    fun `distance inconnue bloque par prudence sans chiffre`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertTrue(decision.blocked)
        assertFalse(decision.stop)
        assertEquals("Va à gauche.", Guidance.phrase(decision))
        assertEquals("Go left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `objet lointain au centre ne bloque pas`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 10f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `phrase anglaise avec distance`() {
        val decision = Guidance.decide(listOf(placed("person", 0.5f, 2f)))

        assertEquals("Go left, 2 meters.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `obstacle tres proche demande l'arret`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 0.4f)))

        assertTrue(decision.blocked)
        assertTrue(decision.stop)
        assertEquals("Stop.", Guidance.phrase(decision))
        assertEquals("Stop.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `le passage en stop fait reparler dans le meme seau`() {
        val roule = Guidance.decide(listOf(placed("personne", 0.5f, 0.65f)))
        val stop = Guidance.decide(listOf(placed("personne", 0.5f, 0.55f)))

        assertFalse(roule.stop)
        assertTrue(stop.stop)
        assertNotEquals(Guidance.key(roule), Guidance.key(stop))
        assertEquals("b|personne|Left|1|12h|stop", Guidance.key(stop))
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
        assertEquals("Va à droite, 2 mètres.", Guidance.phrase(onze))
    }

    @Test
    fun `decalage nul sans distance, signature point d interrogation`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertNull(decision.shiftMeters)
        assertEquals("b|personne|Left|?|12h", Guidance.key(decision))
    }

    @Test
    fun `decalage grandit avec la distance et fait reparler`() {
        val pres = Guidance.decide(listOf(placed("personne", 0.5f, 2f)))
        val loin = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))

        assertEquals(1f, pres.shiftMeters!!, 0.001f)
        assertEquals(1.5f, loin.shiftMeters!!, 0.001f)
        assertNotEquals(Guidance.key(pres), Guidance.key(loin))
    }

    @Test
    fun `le sol marchable ne bloque pas le couloir`() {
        val decision = Guidance.decide(listOf(placed("sidewalk", 0.5f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `la bordure bloque toujours, elle se monte`() {
        val decision = Guidance.decide(listOf(placed("curb", 0.5f)))

        assertTrue(decision.blocked)
    }
}
