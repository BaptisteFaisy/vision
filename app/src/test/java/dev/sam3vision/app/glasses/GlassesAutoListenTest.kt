package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Écoute auto d'ouverture de session ([GlassesAutoListen]) : armée à
 * l'attache caméra, tirée au premier flux montant, une seule fois.
 */
class GlassesAutoListenTest {

    @Test
    fun `le premier flux montant ouvre l ecoute, une seule fois`() {
        var calls = 0
        val gate = GlassesAutoListen { calls += 1 }
        gate.arm()

        gate.onStreaming(true)
        gate.onStreaming(true)

        assertEquals(1, calls)
    }

    @Test
    fun `sans armement, le flux n ouvre rien`() {
        var calls = 0
        val gate = GlassesAutoListen { calls += 1 }

        gate.onStreaming(true)

        assertEquals(0, calls)
    }

    @Test
    fun `la descente du flux n ouvre rien`() {
        var calls = 0
        val gate = GlassesAutoListen { calls += 1 }
        gate.arm()

        gate.onStreaming(false)

        assertEquals(0, calls)
    }

    @Test
    fun `desarmee, la montee suivante n ouvre rien`() {
        var calls = 0
        val gate = GlassesAutoListen { calls += 1 }
        gate.arm()
        gate.disarm()

        gate.onStreaming(true)

        assertEquals(0, calls)
    }

    @Test
    fun `chaque session rearme sa propre ecoute`() {
        var calls = 0
        val gate = GlassesAutoListen { calls += 1 }
        gate.arm()
        gate.onStreaming(true)
        gate.disarm()
        gate.arm()

        gate.onStreaming(true)

        assertEquals(2, calls)
    }
}
