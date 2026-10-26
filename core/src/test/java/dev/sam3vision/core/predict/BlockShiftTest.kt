package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Block-matching : le flot doit retrouver un décalage connu et rester muet sinon. */
class BlockShiftTest {

    private fun mask(width: Int, height: Int, f: (Int, Int) -> Int): GrayMask {
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = f(x, y).toByte()
            }
        }
        return GrayMask(width, height, pixels)
    }

    /** Texture riche mais lisse, comme une vraie image : le SAD y est unimodal. */
    private fun texture(x: Int, y: Int): Int {
        val v = 60 + 45 * sin(x * 0.63) + 40 * sin(y * 0.87) +
            28 * sin((x + y) * 0.29) + 18 * sin(x * 1.9 + y * 0.7)
        return v.toInt().coerceIn(0, 255)
    }

    private val box = Box(20f, 20f, 90f, 80f)

    private fun shifted(dx: Int, dy: Int) =
        mask(120, 120) { x, y -> texture(x - dx, y - dy) }

    @Test
    fun `retrouve un decalage connu`() {
        val found = estimateShift(mask(120, 120, ::texture), shifted(5, 3), box)!!
        assertEquals(5, found.dx)
        assertEquals(3, found.dy)
        assertTrue(found.confidence > 0.8f)
    }

    @Test
    fun `rend 0,0 sur deux images identiques`() {
        val same = mask(120, 120, ::texture)
        val found = estimateShift(same, same, box)!!
        assertEquals(0, found.dx)
        assertEquals(0, found.dy)
    }

    @Test
    fun `reste muet sur une image plate`() {
        val flat = mask(120, 120) { _, _ -> 100 }
        val found = estimateShift(flat, flat, box)!!
        assertFalse(found.trustworthy)
    }

    @Test
    fun `ignore une boite trop petite pour etre sure`() {
        assertNull(estimateShift(mask(120, 120, ::texture), shifted(4, 0), Box(10f, 10f, 26f, 26f)))
    }

    @Test
    fun `refuse deux images de tailles differentes`() {
        assertNull(estimateShift(mask(120, 120, ::texture), mask(64, 64, ::texture), box))
    }

    @Test
    fun `une boite hors champ ne fait pas planter l'estimation`() {
        val found = estimateShift(mask(120, 120, ::texture), shifted(2, 2), Box(100f, 100f, 400f, 400f))
        assertTrue(found == null || found.dx in -8..8)
    }

    @Test
    fun `la confiance croit avec le gain`() {
        val prev = mask(120, 120, ::texture)
        val good = estimateShift(prev, shifted(6, -4), box)!!
        val noise = mask(120, 120) { x, y -> texture(x, y) xor ((x * 31 + y * 17) % 3) }
        val weak = estimateShift(prev, noise, box)!!
        assertTrue(good.confidence > weak.confidence)
    }
}