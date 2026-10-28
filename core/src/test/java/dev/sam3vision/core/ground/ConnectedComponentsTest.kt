package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectedComponentsTest {

    private fun stamp(mask: BooleanArray, w: Int, y0: Int, y1: Int, x0: Int, x1: Int) {
        for (y in y0..y1) {
            for (x in x0..x1) {
                mask[y * w + x] = true
            }
        }
    }

    @Test
    fun `label separe deux carres et mesure les aires`() {
        val w = 10
        val h = 10
        val mask = BooleanArray(w * h)
        stamp(mask, w, 1, 3, 1, 3)
        stamp(mask, w, 6, 7, 6, 8)

        val labeled = ConnectedComponents.label(mask, w, h)

        assertEquals(2, labeled.count)
        assertEquals(9, labeled.areas[1])
        assertEquals(6, labeled.areas[2])
    }

    @Test
    fun `label fusionne en diagonale comme scipy`() {
        val single = BooleanArray(9)
        single[0] = true
        single[4] = true
        assertEquals(1, ConnectedComponents.label(single, 3, 3).count)

        val apart = BooleanArray(9)
        apart[0] = true
        apart[2] = true
        assertEquals(2, ConnectedComponents.label(apart, 3, 3).count)
    }

    @Test
    fun `label vide ou incoherent rend zero`() {
        assertEquals(0, ConnectedComponents.label(BooleanArray(16), 4, 4).count)
        assertEquals(0, ConnectedComponents.label(BooleanArray(3), 4, 4).count)
    }

    @Test
    fun `erode casse un pont d-un pixel`() {
        val w = 14
        val h = 6
        val mask = BooleanArray(w * h)
        stamp(mask, w, 1, 4, 1, 4)
        stamp(mask, w, 1, 4, 9, 12)
        stamp(mask, w, 2, 3, 5, 8)

        assertEquals(1, ConnectedComponents.label(mask, w, h).count)
        val eroded = ConnectedComponents.erode(mask, w, h)
        assertEquals(2, ConnectedComponents.label(eroded, w, h).count)
    }

    @Test
    fun `erode reduit dilate regonfle`() {
        val w = 5
        val mask = BooleanArray(w * w)
        stamp(mask, w, 1, 3, 1, 3)

        val eroded = ConnectedComponents.erode(mask, w, w)
        assertEquals(1, countTrue(eroded))
        val back = ConnectedComponents.dilate(eroded, w, w)
        assertEquals(9, countTrue(back))
    }
}
