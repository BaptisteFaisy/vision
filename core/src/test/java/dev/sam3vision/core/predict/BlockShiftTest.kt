package dev.sam3vision.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Block-matching : le flot doit retrouver un décalage connu et rester muet sinon. */
class BlockShiftTest {

    private val box = Box(20f, 20f, 90f, 80f)

    private fun shifted(dx: Int, dy: Int) =
        ShiftScenes.image(120, 120) { x, y -> ShiftScenes.texture(x - dx, y - dy) }

    @Test
    fun `retrouve un decalage connu`() {
        val found = estimateShift(ShiftScenes.image(120, 120), shifted(5, 3), box)!!
        assertEquals(5, found.dx)
        assertEquals(3, found.dy)
        assertTrue(found.confidence > 0.8f)
    }

    @Test
    fun `rend 0,0 sur deux images identiques`() {
        val same = ShiftScenes.image(120, 120)
        val found = estimateShift(same, same, box)!!
        assertEquals(0, found.dx)
        assertEquals(0, found.dy)
    }

    @Test
    fun `reste muet sur une image plate`() {
        val flat = ShiftScenes.image(120, 120) { _, _ -> 100 }
        val found = estimateShift(flat, flat, box)!!
        assertFalse(found.trustworthy)
    }

    @Test
    fun `ignore une boite trop petite pour etre sure`() {
        assertNull(estimateShift(ShiftScenes.image(120, 120), shifted(4, 0), Box(10f, 10f, 26f, 26f)))
    }

    @Test
    fun `refuse deux images de tailles differentes`() {
        assertNull(estimateShift(ShiftScenes.image(120, 120), ShiftScenes.image(64, 64), box))
    }

    @Test
    fun `une boite hors champ ne fait pas planter l'estimation`() {
        val found = estimateShift(ShiftScenes.image(120, 120), shifted(2, 2), Box(100f, 100f, 400f, 400f))
        assertTrue(found == null || found.dx in -8..8)
    }

    @Test
    fun `la confiance croit avec le gain`() {
        val prev = ShiftScenes.image(120, 120)
        val good = estimateShift(prev, shifted(6, -4), box)!!
        val noise = ShiftScenes.image(120, 120) { x, y -> ShiftScenes.texture(x, y) xor ((x * 31 + y * 17) % 3) }
        val weak = estimateShift(prev, noise, box)!!
        assertTrue(good.confidence > weak.confidence)
    }
}