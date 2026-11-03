package dev.sam3vision.core.crossing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Latch d'entrée « sur le passage » ([CrossingArrivalWatcher.announced]) :
 * faux jusqu'aux 2 frames d'arrivée, vrai ensuite jusqu'à la prochaine frame
 * non arrivée ou [reset] — le déclencheur sur-passage y lit l'arrivée
 * annoncée, sans relire la phrase (dite une seule fois).
 */
class CrossingArrivalLatchTest {

    @Test
    fun `announced faux avant les 2 frames requises`() {
        val watch = CrossingArrivalWatcher()
        assertFalse(watch.announced)
        assertNull(watch.onFrame(true))
        assertFalse("1 frame : pas encore annoncé", watch.announced)
    }

    @Test
    fun `announced vrai apres 2 frames et le reste`() {
        val watch = CrossingArrivalWatcher()
        watch.onFrame(true)
        assertNotNull(watch.onFrame(true))
        assertTrue(watch.announced)
        assertNull("dit une fois, latch gardé", watch.onFrame(true))
        assertTrue(watch.announced)
    }

    @Test
    fun `une frame non arrivee retombe le latch`() {
        val watch = CrossingArrivalWatcher()
        watch.onFrame(true)
        watch.onFrame(true)
        assertTrue(watch.announced)
        assertNull(watch.onFrame(false))
        assertFalse(watch.announced)
    }

    @Test
    fun `reset retombe le latch`() {
        val watch = CrossingArrivalWatcher()
        watch.onFrame(true)
        watch.onFrame(true)
        watch.reset()
        assertFalse(watch.announced)
    }
}
