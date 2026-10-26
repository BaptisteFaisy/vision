package dev.sam3vision.core.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Format `rle` du codec masques : runs LEB128, ligne majeure, premier run = fond. Le format
 * est **sans perte**, donc un raster encodé puis décodé doit revenir identique au bit près ;
 * et tout corps qui ne boucle pas exactement sur la grille est refusé plutôt que dessiné de
 * travers (un masque faux est pire qu'aucun masque : l'écran dessine la boîte).
 */
class MaskRleTest {

    @Test
    fun `un raster encode puis decode se relit au bit pres`() {
        val raster = Shapes.disk(32, 18, 7)
        val decoded = decodeRleMask(Rle.b64(Rle.encode(raster)), 32, 18)

        assertNotNull(decoded)
        assertEquals(32, decoded!!.width)
        assertEquals(18, decoded.height)
        assertArrayEquals(raster.map { it.toByte() }.toByteArray(), decoded.pixels)
    }

    @Test
    fun `le premier run est du fond`() {
        // Le contrat ne porte aucun drapeau : le premier run est toujours du fond, donc un
        // raster qui commence par de l'objet revient inversé. C'est la règle, pas un bug.
        val decoded = decodeRleMask(Rle.b64(Rle.encode(intArrayOf(255, 255, 0, 0))), 4, 1)

        assertNotNull(decoded)
        assertEquals(0, decoded!!.levelAt(0, 0))
        assertEquals(255, decoded.levelAt(2, 0))
    }

    @Test
    fun `une somme de runs qui ne remplit pas la grille est refusee`() {
        val runs = Rle.b64(Rle.encode(intArrayOf(0, 0, 0, 255)))

        assertNull("4 pixels décodés pour une grille de 16", decodeRleMask(runs, 4, 4))
    }

    @Test
    fun `un varint tronque est refuse`() {
        assertNull(decodeRleBytes(byteArrayOf(0x80.toByte()), 2, 1))
        assertNull(decodeRleBytes(byteArrayOf(0x01, 0x80.toByte()), 2, 1))
    }

    @Test
    fun `un varint au dela de la capacite d un Int est refuse`() {
        val hostile = byteArrayOf(
            0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10,
        )

        assertNull(decodeRleBytes(hostile, 2, 1))
    }

    @Test
    fun `un corps vide ou absent est refuse`() {
        assertNull(decodeRleBytes(ByteArray(0), 2, 2))
        assertNull(decodeRleMask("", 2, 2))
        assertNull(decodeRleMask("pas du base64 !!!", 2, 2))
    }

    @Test
    fun `une grille de plus de 4 Mpx est refusee avant allocation`() {
        assertNull(decodeRleBytes(byteArrayOf(0x01), 3000, 3000))
        assertNull(decodeRleBytes(byteArrayOf(0x01), 0, 12))
    }

    @Test
    fun `un base64 precede de son type MIME est tolere`() {
        val runs = Rle.encode(Shapes.raster(6, 2) { x, _ -> x < 3 })
        val decoded = decodeRleMask("data:image/png;base64," + Rle.b64(runs), 6, 2)

        assertNotNull(decoded)
        assertEquals(0, decoded!!.levelAt(0, 0))
        assertEquals(255, decoded.levelAt(3, 0))
        assertEquals(0, decoded.levelAt(0, 1))
    }
}