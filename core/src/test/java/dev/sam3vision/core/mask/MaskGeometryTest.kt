package dev.sam3vision.core.mask

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Géométrie de masque pour les distances : l'étendue mesure la hauteur de l'objet (pas de
 * sa boîte), le pied donne sa ligne d'appui, et les garde-fous (bord, fragmentation)
 * signalent les mesures tronquées. Le poly se lit sans décodage plein.
 */
class MaskGeometryTest {

    /** Masque `w × h` dont les pixels objet sont ceux où [isObject] rend vrai. */
    private fun gray(w: Int, h: Int, isObject: (x: Int, y: Int) -> Boolean): GrayMask {
        val pixels = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (isObject(x, y)) pixels[y * w + x] = 255.toByte()
            }
        }
        return GrayMask(w, h, pixels)
    }

    @Test
    fun `un rectangle avec trou donne bbox pleine et pixels troues`() {
        val mask = gray(10, 8) { x, y -> x in 2..6 && y in 1..5 && x !in 3..4 }

        val extent = extentOf(mask)

        assertNotNull(extent)
        assertEquals(MaskExtent(topRow = 1, bottomRow = 5, leftCol = 2, rightCol = 6, pixels = 15), extent)
        assertEquals("hauteur bbox inclusive", 5, heightPx(extent!!))
        assertEquals("le pied est la derniere ligne", 5, footRow(extent))
    }

    @Test
    fun `un masque vide ou sans objet n a pas d etendue`() {
        assertNull(extentOf(gray(4, 3) { _, _ -> false }))
        assertNull(extentOf(GrayMask(0, 3, ByteArray(0))))
        assertNull(extentOf(GrayMask(4, 3, ByteArray(2))))
    }

    @Test
    fun `le seuil objet est 128 comme GrayMask isObject`() {
        val pixels = ByteArray(2) { 127.toByte() }
        pixels[1] = 128.toByte()

        assertEquals(MaskExtent(0, 0, 1, 1, 1), extentOf(GrayMask(2, 1, pixels)))
    }

    @Test
    fun `le poly donne sa bbox sans decodage plein`() {
        val extent = extentFromPoly(listOf(listOf(2, 1, 6, 1, 6, 5, 2, 5)), 1f)

        assertEquals(MaskExtent(topRow = 1, bottomRow = 5, leftCol = 2, rightCol = 6, pixels = 25), extent)
    }

    @Test
    fun `le poly applique maskScale grille vers frame`() {
        // Grille moitié de la frame : les sommets doublent.
        val extent = extentFromPoly(listOf(listOf(2, 1, 6, 1, 6, 5, 2, 5)), 0.5f)

        assertNotNull(extent)
        assertEquals(2, extent!!.topRow)
        assertEquals(10, extent.bottomRow)
        assertEquals(4, extent.leftCol)
        assertEquals(12, extent.rightCol)
    }

    @Test
    fun `le poly fou rend null sans exception`() {
        assertNull(extentFromPoly(emptyList(), 1f))
        assertNull(extentFromPoly(listOf(emptyList(), listOf(7)), 1f))
        assertNull(extentFromPoly(listOf(listOf(0, 0, 4, 4)), 0f))
        assertNull(extentFromPoly(listOf(listOf(0, 0, 4, 4)), -1f))
        assertNull(extentFromPoly(listOf(listOf(0, 0, 4, 4)), Float.NaN))
        assertNull(extentFromPoly(listOf(listOf(0, 0, 4, 4)), Float.POSITIVE_INFINITY))
    }

    @Test
    fun `le contact haut bas est detecte a tolerance pres`() {
        val h = 8
        assertTrue(touchesBorder(MaskExtent(0, 3, 1, 4, 8), 10, h))
        assertTrue(touchesBorder(MaskExtent(4, 7, 1, 4, 8), 10, h))
        assertTrue("a 2 px du bas avec la tolerance par defaut", touchesBorder(MaskExtent(2, 5, 1, 4, 8), 10, h))
        assertFalse(touchesBorder(MaskExtent(3, 4, 1, 4, 4), 10, h))
        assertFalse("tolerance nulle : seul le contact franc compte", touchesBorder(MaskExtent(1, 5, 1, 4, 8), 10, h, 0f))
        assertFalse(touchesBorder(MaskExtent(0, 3, 1, 4, 8), 0, h))
    }

    @Test
    fun `deux ilots eloignes sont fragmentes un rectangle plein non`() {
        val sparse = gray(10, 10) { x, y -> (x == 1 && y == 1) || (x == 8 && y == 8) }
        val full = gray(10, 10) { x, y -> x in 2..6 && y in 1..5 }

        assertTrue(isFragmented(sparse, extentOf(sparse)!!))
        assertFalse(isFragmented(full, extentOf(full)!!))
        assertTrue("etendue degeneree : mesure inutilisable", isFragmented(full, MaskExtent(3, 1, 5, 2, 0)))
    }
}
