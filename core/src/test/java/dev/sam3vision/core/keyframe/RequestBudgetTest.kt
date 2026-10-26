package dev.sam3vision.core.keyframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le seau par défaut de la lane L13 : 3 req/s, rafale 2, un jeton d'urgence. */
class RequestBudgetTest {

    @Test
    fun `le seau demarre plein et accorde la rafale`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(0L))
        assertTrue(budget.allow(0L))
        assertFalse("rafale de 2 seulement", budget.allow(0L))
    }

    @Test
    fun `la reconstitution suit le debit de 3 par seconde`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(0L))
        assertTrue(budget.allow(0L))
        assertFalse(budget.allow(333L))
        assertTrue("un jeton complet après 334 ms", budget.allow(334L))
    }

    @Test
    fun `la reconstitution ne depasse jamais la rafale`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(10_000L))
        assertTrue(budget.allow(10_000L))
        assertFalse("10 s d'attente ne valent pas plus de 2 jetons", budget.allow(10_000L))
    }

    @Test
    fun `une horloge qui recule ne rajoute rien`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(0L))
        assertTrue(budget.allow(0L))
        assertFalse(budget.allow(-5_000L))
        assertFalse(budget.allow(0L))
    }

    @Test
    fun `le jeton d urgence n est emprunte qu une fois`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(0L))
        assertTrue(budget.allow(0L))
        assertTrue("coupure de scène : la 3e part à découvert", budget.allowUrgent(0L))
        assertFalse("jamais deux découverts à la fois", budget.allowUrgent(0L))
    }

    @Test
    fun `le decouvert est rembourse avant tout nouvel octroi`() {
        val budget = RequestBudget(3f, 2)
        assertTrue(budget.allow(0L))
        assertTrue(budget.allow(0L))
        assertTrue(budget.allowUrgent(0L))
        assertFalse("le jeton reconstitué rembourse la dette", budget.allow(334L))
        assertTrue(budget.allow(1_000L))
    }

    @Test
    fun `msUntilToken annonce l attente du prochain jeton`() {
        val budget = RequestBudget(3f, 1)
        assertEquals(0L, budget.msUntilToken(0L))
        assertTrue(budget.allow(0L))
        assertEquals("1 jeton à 3/s = 334 ms", 334L, budget.msUntilToken(0L))
        assertEquals(234L, budget.msUntilToken(100L))
    }
}