package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.MaskFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SAD masqué ([estimateMaskedShift]) : la mesure porte sur les pixels de l'objet,
 * le fond du rectangle ne la pollue plus. L'objet (24×24, motif distinct du fond)
 * donne 36 points échantillonnés, au-dessus de `MIN_SAMPLES`.
 */
class MaskShiftTest {

    private val box = Box(20f, 20f, 100f, 100f)

    /** Objet de référence, dans [box] avec marge pour un décalage de ±6 px. */
    private val obj = Box(OBJ_X0.toFloat(), OBJ_Y0.toFloat(), (OBJ_X0 + OBJ_EDGE).toFloat(), (OBJ_Y0 + OBJ_EDGE).toFloat())

    /** Niveau de l'objet : la même texture, décalée de moitié — distincte du fond. */
    private fun objectLevel(x: Int, y: Int): Int = (ShiftScenes.texture(x, y) + 128) % 256

    /**
     * [base] avec l'objet peint à [at] : le motif suit l'objet (peint à [at], ancré
     * à [obj]), donc un objet déplacé porte son motif déplacé.
     */
    private fun withObject(base: GrayMask, at: Box): GrayMask {
        val out = GrayMask(base.width, base.height, base.pixels.copyOf())
        val ax = at.x1.toInt() - OBJ_X0
        val ay = at.y1.toInt() - OBJ_Y0
        for (y in at.y1.toInt() until at.y2.toInt()) {
            for (x in at.x1.toInt() until at.x2.toInt()) {
                out.pixels[y * out.width + x] = objectLevel(x - ax, y - ay).toByte()
            }
        }
        return out
    }

    private fun objectMask(): GrayMask = MaskFixtures.rect(SIZE, SIZE, OBJ_X0, OBJ_Y0, OBJ_X0 + OBJ_EDGE, OBJ_Y0 + OBJ_EDGE)

    @Test
    fun `retrouve le decalage de l objet sur fond fixe`() {
        val prev = withObject(ShiftScenes.image(SIZE, SIZE), obj)
        val cur = withObject(ShiftScenes.image(SIZE, SIZE), obj.translated(5f, 0f))
        val found = estimateMaskedShift(prev, cur, box, objectMask())!!
        assertEquals(5, found.dx)
        assertEquals(0, found.dy)
        assertTrue(found.trustworthy)
    }

    @Test
    fun `ignore un fond mobile hors du masque`() {
        val prev = withObject(ShiftScenes.image(SIZE, SIZE), obj)
        val background = ShiftScenes.image(SIZE, SIZE) { x, y -> ShiftScenes.texture(x - 5, y - 3) }
        val cur = withObject(background, obj)
        val masked = estimateMaskedShift(prev, cur, box, objectMask())!!
        assertEquals(0, masked.dx)
        assertEquals(0, masked.dy)
        val plain = estimateShift(prev, cur, box)!!
        assertEquals("le SAD plein rectangle suit le fond, pas l'objet", 5, plain.dx)
        assertEquals("le SAD plein rectangle suit le fond, pas l'objet", 3, plain.dy)
    }

    @Test
    fun `reste muet quand le masque est vide dans la zone`() {
        val prev = ShiftScenes.image(SIZE, SIZE)
        val cur = ShiftScenes.image(SIZE, SIZE) { x, y -> ShiftScenes.texture(x - 5, y - 3) }
        val empty = GrayMask(SIZE, SIZE, ByteArray(SIZE * SIZE))
        assertNull(estimateMaskedShift(prev, cur, box, empty))
    }

    @Test
    fun `refuse un masque d une autre grille`() {
        val prev = ShiftScenes.image(SIZE, SIZE)
        val small = MaskFixtures.rect(64, 64, 8, 8, 40, 40)
        assertNull(estimateMaskedShift(prev, prev, box, small))
    }

    @Test
    fun `refuse deux images de tailles differentes`() {
        val prev = ShiftScenes.image(SIZE, SIZE)
        assertNull(estimateMaskedShift(prev, ShiftScenes.image(64, 64), box, objectMask()))
    }

    private companion object {
        /** Grille carrée des scènes. */
        const val SIZE = 120

        /** Origine de l'objet de référence. */
        const val OBJ_X0 = 44
        const val OBJ_Y0 = 44

        /** Côté de l'objet : 24 px → 36 points échantillonnés (pas 4). */
        const val OBJ_EDGE = 24
    }
}
