package dev.sam3vision.core.stairs

import dev.sam3vision.core.local.LumaPlane
import dev.sam3vision.core.mask.Rle
import dev.sam3vision.core.mask.Shapes
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plan du masque de l'escalier : RLE/poly du direct → plan à l'échelle du
 * compteur ; `null` (repli boîte) sans escalier, sans masque ou masque
 * inutilisable — jamais d'exception.
 */
class StairMaskPlaneTest {

    private fun stairs(
        b64: String,
        encoding: String,
        scale: Float,
        poly: List<List<Int>> = emptyList(),
        label: String = "stairs going up",
    ) = SegObject(label, 0.9f, listOf(0f, 0f, 100f, 100f), b64, encoding, scale, poly)

    /** Lignes [fromRow, fromRow + rows) du plan, pour lire une bande franche. */
    private fun band(plane: ByteArray, fromRow: Int, rows: Int): List<Byte> =
        plane.drop(LumaPlane.WIDTH * fromRow).take(LumaPlane.WIDTH * rows)

    @Test
    fun `masque rle = plan a l'echelle du compteur`() {
        // Grille 10×10, moitié basse = escalier ; frame 100×100, donc scale 0,1.
        val levels = Shapes.raster(10, 10) { _, y -> y >= 5 }
        val obj = stairs(Rle.b64(Rle.encode(levels)), MASK_ENCODING_RLE, 0.1f)
        val plane = stairsMaskPlaneOf(listOf(obj), 100, 100)!!

        assertEquals(LumaPlane.SIZE, plane.size)
        assertTrue(band(plane, 0, 40).all { it == 0.toByte() })
        assertTrue(band(plane, 63, 40).all { it != 0.toByte() })
    }

    @Test
    fun `masque poly = plan rempli`() {
        // Rectangle (1,6)-(9,9) dans une grille 10×10.
        val ring = listOf(1, 6, 9, 6, 9, 9, 1, 9)
        val obj = stairs("", MASK_ENCODING_POLY, 0.1f, listOf(ring))
        val plane = stairsMaskPlaneOf(listOf(obj), 100, 100)!!

        assertEquals(LumaPlane.SIZE, plane.size)
        assertTrue(band(plane, 0, 40).all { it == 0.toByte() })
        assertTrue(band(plane, 65, 15).all { it != 0.toByte() })
    }

    @Test
    fun `sans escalier ni masque = null`() {
        val levels = Shapes.raster(10, 10) { _, y -> y >= 5 }
        val chair = stairs(Rle.b64(Rle.encode(levels)), MASK_ENCODING_RLE, 0.1f, label = "chair")
        val none = stairs("", MASK_ENCODING_NONE, 1f)
        val rle = stairs(Rle.b64(Rle.encode(levels)), MASK_ENCODING_RLE, 0.1f)

        assertNull(stairsMaskPlaneOf(emptyList(), 100, 100))
        assertNull(stairsMaskPlaneOf(listOf(chair), 100, 100))
        assertNull(stairsMaskPlaneOf(listOf(none), 100, 100))
        assertNull(stairsMaskPlaneOf(listOf(rle), 0, 100))
    }

    @Test
    fun `runs corrompus = null sans exception`() {
        val garbage = stairs("!!!pas-du-base64!!!", MASK_ENCODING_RLE, 0.1f)
        val short = stairs(Rle.b64(byteArrayOf(10)), MASK_ENCODING_RLE, 0.1f)

        assertNull(stairsMaskPlaneOf(listOf(garbage), 100, 100))
        assertNull(stairsMaskPlaneOf(listOf(short), 100, 100))
    }

    @Test
    fun `masque vide = null, repli boite`() {
        val levels = IntArray(100) { 0 }
        val obj = stairs(Rle.b64(Rle.encode(levels)), MASK_ENCODING_RLE, 0.1f)

        assertNull(stairsMaskPlaneOf(listOf(obj), 100, 100))
    }
}
