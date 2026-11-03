package dev.sam3vision.app.isee

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Porte de cadence du guidage lunettes : fenêtre [GUIDANCE_FRAME_MS] en guidage, tout
 * passe hors guidage. Ordre user du 03/11 : la fenêtre reste sous les 200 ms.
 */
class GuidanceGateTest {

    @Test
    fun `hors guidage, tout passe`() {
        val gate = GuidanceGate()
        assertTrue(gate.admits(false, 1_000L))
        assertTrue(gate.admits(false, 1_000L))
    }

    @Test
    fun `en guidage, la premiere frame passe puis la fenetre bloque`() {
        val gate = GuidanceGate()
        assertTrue(gate.admits(true, 1_000L))
        assertFalse(gate.admits(true, 1_000L + GUIDANCE_FRAME_MS - 1))
        assertTrue(gate.admits(true, 1_000L + GUIDANCE_FRAME_MS))
    }

    @Test
    fun `la fenetre de guidage reste sous les 200 ms`() {
        assertTrue(GUIDANCE_FRAME_MS in 1 until 200)
    }
}
