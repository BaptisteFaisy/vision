package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

class PedLampMixTest {

    private val dark = 0xFF101010.toInt()
    private val green = 0xFF00FF00.toInt()
    private val red = 0xFFFF0000.toInt()

    /** Crop 48×48 : fond sombre, rectangles pleins [color]. */
    private fun crop(vararg rects: Rect): IntArray {
        val argb = IntArray(48 * 48) { dark }
        for (r in rects) {
            for (y in r.y0..r.y1) for (x in r.x0..r.x1) argb[y * 48 + x] = r.color
        }
        return argb
    }

    private class Rect(val color: Int, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** Bonhomme troué : deux barres 2 px, boîte 10×20, remplissage 0,40. */
    private fun bars(color: Int, y0: Int, y1: Int): Array<Rect> = arrayOf(
        Rect(color, 20, y0, 21, y1),
        Rect(color, 28, y0, 29, y1),
    )

    @Test
    fun `bonhomme vert plus disque rouge disjoint - go`() {
        val argb = crop(Rect(red, 20, 4, 29, 13), *bars(green, 26, 45))
        assertEquals(CrossingState.Green, PedLightGate.verdict(argb, 48, 48))
    }

    @Test
    fun `miroir - disque vert plus bonhomme rouge disjoint, on attend`() {
        val argb = crop(*bars(red, 4, 23), Rect(green, 20, 26, 29, 35))
        assertEquals(CrossingState.Red, PedLightGate.verdict(argb, 48, 48))
    }

    @Test
    fun `panne - deux bonhommes disjoints, on attend`() {
        val argb = crop(*bars(red, 4, 23), *bars(green, 26, 45))
        assertEquals(CrossingState.Red, PedLightGate.verdict(argb, 48, 48))
    }

    @Test
    fun `recouvrement - rouge dans la boite verte, on attend`() {
        val argb = crop(Rect(red, 22, 30, 27, 39), *bars(green, 26, 45))
        assertEquals(CrossingState.Red, PedLightGate.verdict(argb, 48, 48))
    }

    @Test
    fun `bonhomme vert seul - go, sans regression`() {
        val argb = crop(*bars(green, 26, 45))
        assertEquals(CrossingState.Green, PedLightGate.verdict(argb, 48, 48))
    }
}
