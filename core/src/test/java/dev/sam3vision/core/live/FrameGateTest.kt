package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGateTest {

    @Test
    fun `une frame en vol accepte la premiere puis rejette la seconde`() {
        val gate = FrameGate()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
        assertTrue(gate.inFlight)
        assertFalse("2e frame doit etre jetee", gate.tryStart())
        assertFalse(gate.tryStart())
    }

    @Test
    fun `finish libere le creneau`() {
        val gate = FrameGate()
        assertTrue(gate.tryStart())
        gate.finish()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
        gate.finish()
    }

    @Test
    fun `finish sans frame en vol est sans effet`() {
        val gate = FrameGate()
        gate.finish()
        assertFalse(gate.inFlight)
        assertTrue(gate.tryStart())
    }

    @Test
    fun `deux creneaux acceptent deux frames puis rejettent la troisieme`() {
        val gate = FrameGate(2)
        assertEquals(2, gate.capacity)
        assertEquals(2, gate.freeSlots)
        assertTrue(gate.tryStart())
        assertTrue(gate.tryStart())
        assertFalse("3e frame doit etre jetee", gate.tryStart())
        assertEquals(0, gate.freeSlots)
        gate.finish()
        assertEquals(1, gate.freeSlots)
        assertTrue(gate.tryStart())
    }

    @Test
    fun `finish ne descend jamais sous zero`() {
        val gate = FrameGate(2)
        gate.finish()
        gate.finish()
        assertEquals(2, gate.freeSlots)
    }
}
