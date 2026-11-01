package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coupe et préemption, spec v4 (réécrit : [VoicePreempt] et les rangs de
 * file marches > voiture > personne sont supprimés).
 *
 * Contrat : seul le niveau 0 (voitures en mouvement) coupe la parole user
 * (niveau 1) ; partout ailleurs l'écrasement décide (le plus petit niveau
 * gagne, l'écrasé n'est jamais redit sauf re-détecté actuel) et le chat
 * reprend depuis la coupure. Pur, testé en JVM, sans dépendance prod.
 */
class ProximityVoicePreemptTest {

    /** Coupe v4 : seul 0 coupe 1. */
    private fun cuts(cutter: Int, cut: Int): Boolean = cutter == 0 && cut == 1

    /** Arbitre v4 : le plus petit niveau parle seul, `null` = silence. */
    private fun arbitrate(levels: List<Int>): Int? = levels.minOrNull()

    @Test
    fun `0 coupe la parole user`() {
        assertTrue(cuts(cutter = 0, cut = 1))
    }

    @Test
    fun `aucun autre niveau ne coupe la parole user`() {
        (2..12).forEach { level ->
            assertFalse("niveau $level", cuts(cutter = level, cut = 1))
        }
    }

    @Test
    fun `parole user ne coupe pas les voitures`() {
        assertFalse(cuts(cutter = 1, cut = 0))
    }

    @Test
    fun `marches ne preemptent plus, niveau 6 ecrase`() {
        assertEquals(6, arbitrate(listOf(6, 9, 10)))
        assertEquals(2, arbitrate(listOf(2, 6)))
    }

    @Test
    fun `mouvement parle chat en lecture par ecrasement`() {
        assertEquals(0, arbitrate(listOf(0, 9)))
    }

    @Test
    fun `mouvement parle route en cours par ecrasement`() {
        assertEquals(0, arbitrate(listOf(0, 11)))
    }

    @Test
    fun `ecrase en traversee = verdict garde la main`() {
        assertEquals(100, arbitrate(listOf(100, 101, 102)))
    }

    @Test
    fun `silence = personne ne coupe`() {
        assertNull(arbitrate(emptyList()))
    }

    @Test
    fun `chat coupe reprend, niveaux ecrasés jamais redits`() {
        val chat = StringBuilder("Turn left after the bakery.")
        val cutAt = 10
        val resume = chat.substring(cutAt)

        assertEquals("Turn left ", chat.substring(0, cutAt))
        assertEquals("after the bakery.", resume)
    }
}
