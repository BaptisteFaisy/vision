package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reprise après chute d'une session établie ([rescueDelayMs], [rescueForgiven]) :
 * backoff plafonné, budget borné, pardon après une longue séance.
 */
class GlassesSessionRescueTest {

    @Test
    fun `les reprises doublent 1s 2s 4s 8s 16s puis plafonnent a 30s`() {
        assertEquals(1_000L, rescueDelayMs(1))
        assertEquals(2_000L, rescueDelayMs(2))
        assertEquals(4_000L, rescueDelayMs(3))
        assertEquals(8_000L, rescueDelayMs(4))
        assertEquals(16_000L, rescueDelayMs(5))
        assertEquals(30_000L, rescueDelayMs(6))
        assertEquals(30_000L, rescueDelayMs(10))
        assertEquals(30_000L, rescueDelayMs(99))
    }

    @Test
    fun `dix reprises avant de conclure au refus durable`() {
        assertEquals(10, RESCUE_MAX_RETRIES)
        assertEquals(1_000L, RESCUE_BASE_MS)
        assertEquals(30_000L, RESCUE_CAP_MS)
    }

    @Test
    fun `une seance de 60s pardonne les reprises passees`() {
        assertFalse(rescueForgiven(1_000L, 60_999L))
        assertTrue(rescueForgiven(1_000L, 61_000L))
        assertTrue(rescueForgiven(1_000L, 3_601_000L))
        assertEquals(60_000L, RESCUE_FORGIVE_MS)
    }
}
