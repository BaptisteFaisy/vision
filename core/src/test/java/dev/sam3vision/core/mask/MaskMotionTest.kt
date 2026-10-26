package dev.sam3vision.core.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le mouvement du masque ([MaskMotion]) : conversion de grille ([scaledBy]), composition,
 * et bornes d'échelle ([safeScale]) qui décident si le warp accepte une estimation.
 */
class MaskMotionTest {

    private val cx = 16.0
    private val cy = 8.0

    @Test
    fun `scaledBy convertit la translation vers une grille plus fine`() {
        val motion = MaskMotion(dx = 6.0, dy = 3.0, scale = 1.25, confidence = 0.8)
        val converti = motion.scaledBy(2.0)
        assertEquals(3.0, converti.dx, 1e-12)
        assertEquals(1.5, converti.dy, 1e-12)
        assertEquals(1.25, converti.scale, 1e-12)
        assertEquals(0.8, converti.confidence, 1e-12)
        assertEquals(motion, motion.scaledBy(1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `scaledBy refuse une grille de pas nul`() {
        MaskMotion(dx = 1.0).scaledBy(0.0)
    }

    @Test
    fun `compose enchaine deux mouvements`() {
        val premier = MaskMotion(dx = 2.0, dy = -1.0, scale = 1.5)
        val second = MaskMotion(dx = -4.0, dy = 0.5, scale = 0.8)
        val compose = premier.compose(second)
        val etape1 = premier.forward(3.0, 5.0, cx, cy)
        val etape2 = second.forward(etape1[0], etape1[1], cx, cy)
        val direct = compose.forward(3.0, 5.0, cx, cy)
        assertEquals(etape2[0], direct[0], 1e-9)
        assertEquals(etape2[1], direct[1], 1e-9)
        assertEquals(1.2, compose.scale, 1e-9)
    }

    @Test
    fun `source est l inverse de forward`() {
        val motion = MaskMotion(dx = 3.5, dy = -2.25, scale = 1.4)
        val source = motion.source(11.0, 7.0, cx, cy)
        assertTrue(source != null)
        val revenu = motion.forward(source!![0], source[1], cx, cy)
        assertEquals(11.0, revenu[0], 1e-9)
        assertEquals(7.0, revenu[1], 1e-9)
    }

    @Test
    fun `safeScale borne les echelles aberrantes`() {
        assertNull(MaskMotion(scale = Double.NaN).safeScale())
        assertNull(MaskMotion(scale = 0.0).safeScale())
        assertNull(MaskMotion(scale = Double.POSITIVE_INFINITY).safeScale())
        assertNull(MaskMotion(scale = 0.1).safeScale())
        assertNull(MaskMotion(scale = 8.0).safeScale())
        assertEquals(1.0, MaskMotion(scale = 1.0).safeScale()!!, 1e-12)
        assertEquals(2.0, MaskMotion(scale = 2.0).safeScale()!!, 1e-12)
        assertFalse(MaskMotion(dx = Double.NaN).isUsable)
        assertFalse(MaskMotion(dx = 1.0, scale = 0.0).isUsable)
        assertTrue(MaskMotion(dx = 1.0, scale = 1.0).isUsable)
    }

    @Test
    fun `un mouvement nul est l identite`() {
        val motion = MaskMotion()
        assertTrue(motion.isIdentity)
        assertTrue(motion.isUsable)
        val point = motion.forward(4.0, 6.0, cx, cy)
        assertEquals(4.0, point[0], 1e-12)
        assertEquals(6.0, point[1], 1e-12)
        assertFalse(MaskMotion(dx = 0.5).isIdentity)
        assertFalse(MaskMotion(scale = 1.2).isIdentity)
    }
}
