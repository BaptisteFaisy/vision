package dev.sam3vision.core.crossing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Une boîte sans lampe qui clignote n'est pas un feu ; un vrai feu reste un feu. */
class LightGhostGateTest {

    @Test
    fun `boite sans lampe une ou deux frames - jamais un feu`() {
        val gate = LightGhostGate()
        repeat(10) {
            assertFalse(gate.admits(sawBox = true, lampRead = false))
            assertFalse(gate.admits(sawBox = true, lampRead = false))
            assertFalse(gate.admits(sawBox = false, lampRead = false))
        }
    }

    @Test
    fun `boite qui tient trois frames - un feu, meme sans lampe`() {
        val gate = LightGhostGate()
        assertFalse(gate.admits(sawBox = true, lampRead = false))
        assertFalse(gate.admits(sawBox = true, lampRead = false))
        assertTrue(gate.admits(sawBox = true, lampRead = false))
    }

    @Test
    fun `lampe lue - compte des la premiere frame`() {
        assertTrue(LightGhostGate().admits(sawBox = true, lampRead = true))
    }

    @Test
    fun `feu confirme perdu puis revu - il reparle en une frame`() {
        val gate = LightGhostGate()
        gate.admits(sawBox = true, lampRead = true)
        repeat(LightGhostGate.FORGET_FRAMES - 1) { assertFalse(gate.admits(sawBox = false, lampRead = false)) }
        assertTrue(gate.admits(sawBox = true, lampRead = false))
    }

    @Test
    fun `feu oublie apres une longue absence - il se reconfirme`() {
        val gate = LightGhostGate()
        gate.admits(sawBox = true, lampRead = true)
        repeat(LightGhostGate.FORGET_FRAMES) { gate.admits(sawBox = false, lampRead = false) }
        assertFalse(gate.admits(sawBox = true, lampRead = false))
    }

    @Test
    fun `reset - tout est oublie`() {
        val gate = LightGhostGate()
        gate.admits(sawBox = true, lampRead = true)
        gate.reset()
        assertFalse(gate.admits(sawBox = true, lampRead = false))
    }
}
