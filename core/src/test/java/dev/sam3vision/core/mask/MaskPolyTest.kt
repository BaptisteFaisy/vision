package dev.sam3vision.core.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Format `poly` du codec masques : des anneaux entiers remplis en **even-odd**, la règle du
 * serveur. Le poly est le format le plus compact, mais c'est une **approximation** (les
 * sommets sont simplifiés) : le trou d'un anneau doit survivre, sinon un masque en anneau
 * deviendrait un disque plein.
 */
class MaskPolyTest {

    private val rectangle = listOf(1, 1, 4, 1, 4, 3, 1, 3)

    @Test
    fun `un rectangle entier remplit son interieur en centres de pixels`() {
        val mask = decodePolyMask(listOf(rectangle), 6, 5)

        assertNotNull(mask)
        assertEquals(255, mask!!.levelAt(1, 1))
        assertEquals(255, mask.levelAt(3, 2))
        assertEquals(0, mask.levelAt(0, 0))
        assertEquals("le bord droit est exclu (x < 4)", 0, mask.levelAt(4, 1))
        assertEquals("le bord bas est exclu (y < 3)", 0, mask.levelAt(1, 3))
    }

    @Test
    fun `un anneau qui repete son premier sommet se referme tout seul`() {
        // Le même rectangle, une fois refermé implicitement, une fois explicitement : le
        // résultat doit être identique (le serveur n'envoie pas toujours la répétition).
        val explicit = rectangle + 1 + 1

        assertArrayEquals(
            decodePolyMask(listOf(rectangle), 6, 5)!!.pixels,
            decodePolyMask(listOf(explicit), 6, 5)!!.pixels,
        )
    }

    @Test
    fun `le trou d un second anneau survit (even-odd)`() {
        val mask = decodePolyMask(listOf(rectangle, listOf(2, 1, 3, 1, 3, 2, 2, 2)), 6, 5)

        assertNotNull(mask)
        assertEquals("le contour est plein", 255, mask!!.levelAt(1, 1))
        assertEquals("le trou reste vide", 0, mask.levelAt(2, 1))
    }

    @Test
    fun `un anneau de moins de trois sommets ne remplit rien`() {
        assertNull(decodePolyMask(listOf(listOf(1, 1, 4, 3)), 6, 5))
        assertNull(decodePolyMask(emptyList(), 6, 5))
    }

    @Test
    fun `une forme hors de la grille est bornee sans deborder`() {
        val mask = decodePolyMask(listOf(listOf(-5, -5, 20, -5, 20, 20, -5, 20)), 4, 3)

        assertNotNull(mask)
        assertEquals(4 * 3, mask!!.size)
        assertEquals(255, mask.levelAt(0, 0))
        assertEquals(255, mask.levelAt(3, 2))
    }

    @Test
    fun `poly et rle du meme raster donnent le meme masque`() {
        // Les deux formats sortent du même encodeur serveur : le client doit les relire à
        // l'identique, sinon changer de format changerait la forme affichée.
        val width = 8
        val height = 6
        val raster = Shapes.raster(width, height) { x, y -> x in 1..3 && y in 1..2 }
        val viaRle = decodeRleMask(Rle.b64(Rle.encode(raster)), width, height)
        val viaPoly = decodePolyMask(listOf(rectangle), width, height)

        assertNotNull(viaRle)
        assertArrayEquals(viaRle!!.pixels, viaPoly!!.pixels)
    }

    @Test
    fun `une grille inutilisable est refusee`() {
        assertNull(decodePolyMask(listOf(rectangle), 0, 5))
        assertNull(decodePolyMask(listOf(rectangle), 3000, 3000))
    }
}