package dev.sam3vision.core.crossing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingLightDueTest {

    @Test
    fun `jamais dit - le statut est du`() {
        assertTrue(CrossingLightDue().due(0L))
    }

    @Test
    fun `dit - le statut n est plus du avant 4 s`() {
        val due = CrossingLightDue()
        due.markSpoken(1_000L)

        assertFalse(due.due(1_000L))
        assertFalse(due.due(4_999L))
        assertTrue(due.due(5_000L))
    }

    @Test
    fun `redire recule l echeance`() {
        val due = CrossingLightDue()
        due.markSpoken(0L)
        due.markSpoken(4_000L)

        assertFalse(due.due(7_999L))
        assertTrue(due.due(8_000L))
    }

    @Test
    fun `reset - le statut est du a nouveau`() {
        val due = CrossingLightDue()
        due.markSpoken(1_000L)
        due.reset()

        assertTrue(due.due(1_000L))
    }
}
