package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cadence des lectures locales du feu : 150 ms au plus tard, jamais plus vite. */
class LightClockTest {

    @Test
    fun `la premiere frame est toujours due`() {
        assertTrue(LightClock().due(1_000))
    }

    @Test
    fun `deux frames rapprochees ne declenchent qu une lecture`() {
        val clock = LightClock()
        assertTrue(clock.due(1_000))
        assertFalse(clock.due(1_033))
        assertFalse(clock.due(1_100))
        assertFalse(clock.due(1_149))
    }

    @Test
    fun `a 150 ms la lecture est due`() {
        val clock = LightClock()
        assertTrue(clock.due(1_000))
        assertTrue(clock.due(1_150))
    }

    @Test
    fun `le seau se rearme depuis la derniere lecture, pas depuis la premiere`() {
        val clock = LightClock()
        assertTrue(clock.due(1_000))
        assertTrue(clock.due(1_150))
        assertFalse(clock.due(1_200))
        assertTrue(clock.due(1_300))
    }

    @Test
    fun `reset rend la frame suivante due immediatement`() {
        val clock = LightClock()
        assertTrue(clock.due(1_000))
        assertFalse(clock.due(1_050))
        clock.reset()
        assertTrue(clock.due(1_050))
    }

    @Test
    fun `un intervalle explicite est respecte`() {
        val clock = LightClock(maxMs = 500)
        assertTrue(clock.due(0))
        assertFalse(clock.due(499))
        assertTrue(clock.due(500))
    }
}
