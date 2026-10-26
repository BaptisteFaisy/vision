package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Époque du [FrameGate] : ce qui rend un `stop()` réellement arrêtant. Une frame partie
 * avant l'arrêt reste suspendue dans le réseau ; sans époque, son retour libérerait un
 * créneau qu'elle ne tient plus et son résultat serait publié. Les quatre cas ci-dessous
 * sont exactement ce que le régulateur doit garantir.
 */
class FrameGateEraTest {

    @Test
    fun `invalidate rend les creneaux et perime la frame en vol`() {
        val gate = FrameGate()
        val era = gate.generation
        assertTrue(gate.tryStart(era))
        assertTrue(gate.inFlight)

        val next = gate.invalidate()

        assertEquals("l'époque a tourné", era + 1L, next)
        assertEquals(next, gate.generation)
        assertFalse("plus rien en vol", gate.inFlight)
        assertEquals("le créneau est libre", 1, gate.freeSlots)
        assertFalse("la frame partie avant est périmée", gate.isCurrent(era))
    }

    @Test
    fun `une frame d une epoque revolue ne demarre plus`() {
        val gate = FrameGate()
        val era = gate.generation
        gate.invalidate()

        assertFalse("époque révolue : la frame est jetée", gate.tryStart(era))
        assertFalse(gate.inFlight)
        assertTrue("la nouvelle époque, elle, passe", gate.tryStart())
    }

    @Test
    fun `le finish d une epoque revolue ne vole pas le creneau de la suivante`() {
        val gate = FrameGate()
        val stale = gate.generation
        assertTrue(gate.tryStart(stale))
        gate.invalidate()

        assertTrue("la séance suivante prend le créneau", gate.tryStart())
        gate.finish(stale)
        assertTrue("le créneau de la nouvelle frame tient", gate.inFlight)

        gate.finish()
        assertFalse(gate.inFlight)
    }

    @Test
    fun `sans arret le compte des creneaux ne change pas`() {
        val gate = FrameGate(2)
        assertTrue(gate.tryStart())
        assertTrue(gate.tryStart())
        assertFalse("toujours une seule frame de trop", gate.tryStart())
        gate.finish()
        gate.finish()
        gate.finish()
        assertEquals("finish ne descend pas sous zéro", 2, gate.freeSlots)
    }
}