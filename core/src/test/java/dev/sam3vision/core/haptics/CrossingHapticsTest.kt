package dev.sam3vision.core.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CrossingHapticsTest {

    @Test
    fun `le vert vibre deux salves franches`() {
        val wave = HapticPattern.crossingGreen()
        assertEquals(listOf(0L, 90L, 110L, 90L), wave.timings)
        assertEquals(listOf(0, 255, 0, 255), wave.amplitudes)
        assertFalse(wave.looped)
    }

    @Test
    fun `le rouge vibre un creneau long unique`() {
        val wave = HapticPattern.crossingRed()
        assertEquals(listOf(0L, 320L), wave.timings)
        assertEquals(listOf(0, 200), wave.amplitudes)
        assertFalse(wave.looped)
    }
}
