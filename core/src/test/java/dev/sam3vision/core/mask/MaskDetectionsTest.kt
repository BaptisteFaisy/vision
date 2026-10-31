package dev.sam3vision.core.mask

import dev.sam3vision.core.model.MASK_ENCODING_BITS
import dev.sam3vision.core.model.MASK_ENCODING_NONE
import dev.sam3vision.core.model.MASK_ENCODING_PNG
import dev.sam3vision.core.model.MASK_ENCODING_POLY
import dev.sam3vision.core.model.MASK_ENCODING_RLE
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.model.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bbox de masque des détections du chemin distances (`toDetectionsWithExtents`). */
class MaskDetectionsTest {

    private fun seg(
        encoding: String,
        b64: String = "",
        poly: List<List<Int>> = emptyList(),
        scale: Float = 1f,
    ): SegObject = SegObject(
        label = "personne",
        score = 0.9f,
        box = listOf(0f, 0f, 100f, 100f),
        maskPngB64 = b64,
        maskEncoding = encoding,
        maskScale = scale,
        maskPoly = poly,
    )

    @Test
    fun `poly donne sa bbox sans decodage`() {
        val out = listOf(seg(MASK_ENCODING_POLY, poly = listOf(listOf(10, 20), listOf(30, 40))))
            .toDetectionsWithExtents(100, 100)
        assertEquals(listOf(10f, 20f, 31f, 41f), out.first().maskBox)
        assertEquals(listOf(0f, 0f, 100f, 100f), out.first().box)
    }

    @Test
    fun `poly reduit revient en pixels frame`() {
        val out = listOf(seg(MASK_ENCODING_POLY, poly = listOf(listOf(5, 10), listOf(15, 20)), scale = 0.5f))
            .toDetectionsWithExtents(100, 100)
        assertEquals(listOf(10f, 20f, 31f, 41f), out.first().maskBox)
    }

    @Test
    fun `bits pleine resolution donne l'etendue exacte`() {
        val b64 = M1.b64(100, 100, objet = { x, y -> x in 10..29 && y in 20..39 })
        val out = listOf(seg(MASK_ENCODING_BITS, b64)).toDetectionsWithExtents(100, 100)
        assertEquals(listOf(10f, 20f, 30f, 40f), out.first().maskBox)
    }

    @Test
    fun `bits epars ne donne pas de bbox`() {
        val b64 = M1.b64(100, 100, objet = { x, y -> x in 10..29 && y in 20..39 && (x + y) % 7 == 0 })
        val out = listOf(seg(MASK_ENCODING_BITS, b64)).toDetectionsWithExtents(100, 100)
        assertNull(out.first().maskBox)
    }

    @Test
    fun `bits hors dimensions frame refuse`() {
        val b64 = M1.b64(64, 64, objet = { _, _ -> true })
        val out = listOf(seg(MASK_ENCODING_BITS, b64)).toDetectionsWithExtents(100, 100)
        assertNull(out.first().maskBox)
    }

    @Test
    fun `rle reduit revient en pixels frame`() {
        val levels = Shapes.raster(50, 50) { x, y -> x in 5..14 && y in 10..19 }
        val b64 = Rle.b64(Rle.encode(levels))
        val out = listOf(seg(MASK_ENCODING_RLE, b64, scale = 0.5f)).toDetectionsWithExtents(100, 100)
        assertEquals(listOf(10f, 20f, 30f, 40f), out.first().maskBox)
    }

    @Test
    fun `png none et inconnu ne decodent pas sur ce chemin`() {
        for (encoding in listOf(MASK_ENCODING_PNG, MASK_ENCODING_NONE, "fax", "")) {
            val out = listOf(seg(encoding, "AAAA")).toDetectionsWithExtents(100, 100)
            assertNull("encodage $encoding", out.first().maskBox)
        }
        val broken = listOf(seg(MASK_ENCODING_BITS, "!!!")).toDetectionsWithExtents(100, 100)
        assertNull(broken.first().maskBox)
    }

    @Test
    fun `poly hors cadre borne au lieu d'inventer`() {
        val poly = listOf(listOf(-50, -50), listOf(500, 500))
        val out = listOf(seg(MASK_ENCODING_POLY, poly = poly)).toDetectionsWithExtents(100, 100)
        assertEquals(listOf(0f, 0f, 100f, 100f), out.first().maskBox)
    }

    @Test
    fun `objets du tracker enrichis comme les segmentes`() {
        val tracked = TrackedObject(
            id = 3,
            label = "voiture",
            score = 0.8f,
            box = listOf(0f, 0f, 100f, 100f),
            maskPngB64 = "",
            maskEncoding = MASK_ENCODING_POLY,
            maskPoly = listOf(listOf(10, 20), listOf(30, 40)),
        )
        val out = listOf(tracked).toDetectionsWithExtents(100, 100)
        assertEquals(listOf(10f, 20f, 31f, 41f), out.first().maskBox)
    }
}
