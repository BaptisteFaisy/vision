package dev.sam3vision.core.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 4 taps en moins de 3 s : le 4e déclenche, puis le compteur repart de
 * zéro ; hors fenêtre, les vieux taps ne comptent plus.
 */
class QuadrupleTapTest {

    @Test
    fun `quatre taps en trois secondes declenchent au quatrieme`() {
        val rafale = QuadrupleTap()
        assertFalse(rafale.onTap(0))
        assertFalse(rafale.onTap(800))
        assertFalse(rafale.onTap(1600))
        assertTrue(rafale.onTap(2400))
    }

    @Test
    fun `trois taps ne declenchent pas`() {
        val rafale = QuadrupleTap()
        assertFalse(rafale.onTap(0))
        assertFalse(rafale.onTap(800))
        assertFalse(rafale.onTap(1600))
    }

    @Test
    fun `quatrieme tap hors fenetre ne declenche pas`() {
        val rafale = QuadrupleTap()
        rafale.onTap(0)
        rafale.onTap(100)
        rafale.onTap(200)
        assertFalse("3 s dépassées depuis le 1er", rafale.onTap(3100))
    }

    @Test
    fun `apres un declenchement, quatre nouveaux taps redeclenchent`() {
        val rafale = QuadrupleTap()
        var feux = 0
        var moment = 0L
        repeat(8) {
            if (rafale.onTap(moment)) feux++
            moment += 100
        }
        assertEquals(2, feux)
    }

    @Test
    fun `reset oublie les taps en cours`() {
        val rafale = QuadrupleTap()
        rafale.onTap(0)
        rafale.onTap(100)
        rafale.onTap(200)
        rafale.reset()
        assertFalse(rafale.onTap(300))
        assertFalse(rafale.onTap(400))
        assertFalse(rafale.onTap(500))
        assertTrue(rafale.onTap(600))
    }

    @Test
    fun `la fenetre glisse, les vieux taps sortent`() {
        val rafale = QuadrupleTap()
        rafale.onTap(0)
        rafale.onTap(1000)
        rafale.onTap(2000)
        assertFalse(rafale.onTap(3001))
        assertTrue("1000, 2000, 3001, 3100 tiennent en 3 s", rafale.onTap(3100))
    }
}
