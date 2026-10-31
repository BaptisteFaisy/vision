package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidage « tout droit » : couloir central, côté de sortie, quatre mots FR/EN, signature. */
class GuidanceTest {

    private fun placed(label: String, x: Float, meters: Float? = null) =
        PlacedLabel(label, x, 0.5f, meters)

    @Test
    fun `voie libre sans objet`() {
        val decision = Guidance.decide(emptyList())

        assertFalse(decision.blocked)
        assertEquals("Straight.", Guidance.phrase(decision))
        assertEquals("Straight.", Guidance.phrase(decision, SpeechLanguage.En))
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
        assertEquals("Right.", Guidance.phrase(decision))
    }

    @Test
    fun `distance inconnue bloque par prudence`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
        assertEquals("Left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `objet lointain au centre ne bloque pas`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 10f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `mot anglais du cote de sortie`() {
        val decision = Guidance.decide(listOf(placed("person", 0.5f, 2f)))

        assertEquals("Left.", Guidance.phrase(decision, SpeechLanguage.En))
    }

    @Test
    fun `contact mesure dit stop`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f, 0.4f)))

        assertTrue(decision.blocked)
        assertEquals("Stop.", Guidance.phrase(decision))
        assertEquals("Stop.", Guidance.phrase(decision, SpeechLanguage.En))
        assertEquals("w|stop", Guidance.key(decision))
    }

    @Test
    fun `deux obstacles differents, meme mot, pas de reannonce`() {
        val pres = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))
        val loin = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))

        assertEquals(Guidance.key(pres), Guidance.key(loin))
        assertEquals("w|left", Guidance.key(loin))
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
        assertEquals("w|straight", Guidance.key(Guidance.decide(emptyList())))

        val bloque = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))

        assertEquals("w|left", Guidance.key(bloque))
    }

    @Test
    fun `un mot different fait reparler`() {
        val libre = Guidance.decide(emptyList())
        val bloque = Guidance.decide(listOf(placed("personne", 0.5f, 2.4f)))

        assertNotEquals(Guidance.key(libre), Guidance.key(bloque))
    }

    @Test
    fun `le cote de sortie donne le mot`() {
        val onze = Guidance.decide(listOf(placed("personne", 0.36f, 2f)))

        assertEquals("w|right", Guidance.key(onze))
        assertEquals("Right.", Guidance.phrase(onze))
    }

    @Test
    fun `decalage nul sans distance`() {
        val decision = Guidance.decide(listOf(placed("personne", 0.5f)))

        assertNull(decision.shiftMeters)
        assertEquals("w|left", Guidance.key(decision))
    }

    @Test
    fun `le decalage ne fait plus reparler`() {
        val pres = Guidance.decide(listOf(placed("personne", 0.5f, 2f)))
        val loin = Guidance.decide(listOf(placed("personne", 0.5f, 3.4f)))

        assertEquals(1f, pres.shiftMeters!!, 0.001f)
        assertEquals(1.5f, loin.shiftMeters!!, 0.001f)
        assertEquals(Guidance.key(pres), Guidance.key(loin))
    }

    @Test
    fun `le sol marchable ne bloque pas le couloir`() {
        val decision = Guidance.decide(listOf(placed("sidewalk", 0.5f)))

        assertFalse(decision.blocked)
    }

    @Test
    fun `les chemins et la route ne se contournent pas`() {
        val sols = listOf(
            "chemin", "chemins", "sentier", "path", "pathway", "walkway", "trail",
            "route", "routes", "rue", "road", "street", "avenue", "boulevard",
            "allée", "chaussée", "ruelle", "alley",
        )

        for (sol in sols) {
            val decision = Guidance.decide(listOf(placed(sol, 0.5f, 2f)))

            assertFalse(sol, decision.blocked)
            assertEquals("Straight.", Guidance.phrase(decision))
        }
    }

    @Test
    fun `la voie ferree reste un obstacle`() {
        for (rail in listOf("track", "rail", "voie")) {
            val decision = Guidance.decide(listOf(placed(rail, 0.5f, 2f)))

            assertTrue(rail, decision.blocked)
        }
    }

    @Test
    fun `la bordure bloque toujours, elle se monte`() {
        val decision = Guidance.decide(listOf(placed("curb", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
    }

    @Test
    fun `peinture au sol bloque`() {
        val decision = Guidance.decide(listOf(placed("crosswalk", 0.5f)))

        assertTrue(decision.blocked)
        assertEquals("Left.", Guidance.phrase(decision))
    }
}
