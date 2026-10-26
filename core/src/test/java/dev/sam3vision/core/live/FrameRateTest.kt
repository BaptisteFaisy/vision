package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence sur fenêtre glissante : pas de chiffre absurde sur deux frames proches. */
class FrameRateTest {

    @Test
    fun `zero tant qu_il n_y a pas deux instants`() {
        val rate = FrameRate()
        assertEquals(0f, rate.record(1_000L), 0.01f)
        assertEquals(0f, rate.rate, 0.01f)
    }

    @Test
    fun `deux instants a 100 ms donnent 10 ips`() {
        val rate = FrameRate()
        rate.record(1_000L)
        assertEquals(10f, rate.record(1_100L), 0.01f)
    }

    @Test
    fun `deux frames collees ne font pas exploser la cadence`() {
        val rate = FrameRate()
        // 7 frames à 1 s d'écart, puis une huitieme 19 ms apres : moyenne sur la fenetre
        (0..6).forEach { rate.record(it * 1_000L) }
        val fps = rate.record(6_019L)
        assertTrue("cadence bornée, pas 52 ips : $fps", fps < 3f)
    }

    @Test
    fun `reset oublie les instants`() {
        val rate = FrameRate()
        rate.record(0L)
        rate.record(100L)
        rate.reset()
        assertEquals(0f, rate.rate, 0.01f)
        assertEquals(0f, rate.record(500L), 0.01f)
    }
}
