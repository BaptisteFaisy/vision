package dev.sam3vision.core.mask

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Grille et agrandissement du codec : le gain annoncé vient de la **grille réduite** (256 px
 * de côté), pas du format. Il faut donc deux choses pour ne pas mentir à l'écran — déduire la
 * grille du ratio `mask_scale` (un run RLE ne porte aucune dimension) et la ramener à la
 * frame au plus proche voisin, sans décalage cumulé.
 */
class MaskUpscaleTest {

    private fun mask(width: Int, height: Int, levels: IntArray) = GrayMask(
        width,
        height,
        levels.map { it.toByte() }.toByteArray(),
    )

    @Test
    fun `la garde de taille refuse une grille nulle ou demesuree`() {
        assertEquals(12, maskGridSize(4, 3))
        assertNull(maskGridSize(0, 3))
        assertNull(maskGridSize(4, -1))
        assertNull("au-delà de 4 Mpx", maskGridSize(3000, 3000))
        assertEquals("4 Mpx pile", 4 * 1024 * 1024, maskGridSize(2048, 2048))
    }

    @Test
    fun `la grille suit le ratio grille sur frame`() {
        assertEquals(256 to 103, maskGrid(512, 206, 0.5f))
        assertEquals(256 to 256, maskGrid(256, 256, 1f))
        assertEquals("une frame de taille inconnue n'a pas de grille", null, maskGrid(0, 206, 0.5f))
    }

    @Test
    fun `un ratio absurde ne donne pas de grille`() {
        assertNull(maskGrid(512, 206, 0f))
        assertNull(maskGrid(512, 206, -1f))
        assertNull(maskGrid(512, 206, 8f))
        assertNull(maskGrid(512, 206, Float.NaN))
    }

    @Test
    fun `l agrandissement fait des blocs de deux pixels centres`() {
        val small = mask(2, 2, intArrayOf(0, 255, 255, 0))
        val big = upscaleNearest(small, 4, 4)

        assertNotNull(big)
        assertEquals(4, big!!.width)
        assertArrayEquals(
            intArrayOf(
                0, 0, 255, 255,
                0, 0, 255, 255,
                255, 255, 0, 0,
                255, 255, 0, 0,
            ),
            big.pixels.map { it.toInt() and 0xFF }.toIntArray(),
        )
    }

    @Test
    fun `un masque deja a la bonne taille est rendu tel quel`() {
        val source = mask(4, 3, IntArray(12))

        assertSame(source, upscaleNearest(source, 4, 3))
        assertNull("une cible inutilisable ne rend rien", upscaleNearest(source, 0, 3))
    }

    @Test
    fun `la frame cible ramene la grille reduite a la frame`() {
        val small = mask(2, 2, intArrayOf(0, 255, 255, 0))
        val frame = upscaleToFrame(small, 8, 6)

        assertEquals(8, frame.width)
        assertEquals(6, frame.height)
        assertTrue("la moitié haute garde le coin objet", frame.levelAt(6, 0) > 0)
        assertEquals(0, frame.levelAt(0, 0))
    }

    @Test
    fun `une frame de taille inconnue laisse le masque tel quel`() {
        val source = mask(2, 2, intArrayOf(0, 255, 255, 0))

        assertSame(source, upscaleToFrame(source, 0, 0))
    }
}