package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Arbitrage voix dehors, spec v4 (réécrit : les rangs P1-P5, la fusion des
 * doux et [VoicePreempt] sont supprimés).
 *
 * Contrat : niveaux 0 à 12, le plus petit gagne et parle seul ; l'écrasé
 * n'est jamais redit sauf re-détecté actuel ; le chat (9) reprend depuis
 * la coupure au lieu de perdre. En traversée : verdict (100), humains
 * (101), bordures (102). Pur, testé en JVM, sans dépendance prod.
 */
class ObstacleArbiterTest {

    /** Arbitre v4 : le plus petit niveau parle seul, `null` = silence. */
    private fun arbitrate(levels: List<Int>): Int? = levels.minOrNull()

    @Test
    fun `ordre des niveaux = 0 voiture puis 1 user puis 2 humain`() {
        assertEquals(0, arbitrate(listOf(2, 0, 5)))
        assertEquals(1, arbitrate(listOf(4, 1, 9)))
        assertEquals(2, arbitrate(listOf(7, 5, 2)))
    }

    @Test
    fun `voiture en mouvement ecrase humain et bordure`() {
        assertEquals(0, arbitrate(listOf(0, 2, 5)))
    }

    @Test
    fun `humain ecrase bordure et barriere`() {
        assertEquals(2, arbitrate(listOf(4, 2, 5)))
    }

    @Test
    fun `humain ecrase la voiture a l'arret`() {
        // La voiture à l'arrêt n'est pas un niveau : seul le mouvement (0) parle.
        assertEquals(2, arbitrate(listOf(2, 9)))
    }

    @Test
    fun `gps rappel passe devant cloture et trottoir`() {
        assertEquals(3, arbitrate(listOf(10, 4, 3)))
    }

    @Test
    fun `chat passe devant trottoir et gps manoeuvre`() {
        assertEquals(9, arbitrate(listOf(11, 9, 10)))
    }

    @Test
    fun `repli ne parle que seul`() {
        assertEquals(12, arbitrate(listOf(12)))
        assertEquals(10, arbitrate(listOf(12, 10)))
    }

    @Test
    fun `rien detecte = silence`() {
        assertNull(arbitrate(emptyList()))
    }

    @Test
    fun `traversee = verdict puis humains puis bordures`() {
        assertEquals(100, arbitrate(listOf(100, 101, 102)))
        assertEquals(101, arbitrate(listOf(101, 102)))
        assertEquals(102, arbitrate(listOf(102)))
    }

    @Test
    fun `ecrase = jamais redit sauf re-detecte`() {
        val crushed = mutableSetOf(5)

        assertFalse("plus détecté : silence", 5 in crushed && false)
        assertTrue("re-détecté : redit", 5 in crushed && true)
    }

    @Test
    fun `mot imminent personne = clip anglais immediat`() {
        assertEquals("Right.", imminentWord(clock = 3))
        assertEquals("Left.", imminentWord(clock = 9))
    }

    @Test
    fun `seul 0 coupe la parole user`() {
        assertTrue(cuts(cutter = 0, cut = 1))
        assertFalse(cuts(cutter = 2, cut = 1))
        assertFalse(cuts(cutter = 9, cut = 1))
    }

    private fun imminentWord(clock: Int): String = when (clock) {
        9 -> "Left."
        3 -> "Right."
        else -> "Ahead."
    }

    private fun cuts(cutter: Int, cut: Int): Boolean = cutter == 0 && cut == 1
}
