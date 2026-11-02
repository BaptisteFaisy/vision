package dev.sam3vision.core.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compteur d'écrêtage sur pixels synthétiques : les quatre familles qui décident du
 * verdict (blanc pur écrasé, gris, couleur saturée, noir) plus les cas de bord qui
 * séparent « saturé » de « blanc » — c'est exactement la confusion qu'on veut lever
 * (« feu éteint » contre « lampe blanche par surexposition »).
 */
class ClippingTest {

    private fun crop(pixel: Int, count: Int = 16): IntArray = IntArray(count) { pixel }

    @Test
    fun `blanc pur - ecrase jusqu'au dernier pixel`() {
        val stats = Clipping.stats(crop(0xFFFFFFFF.toInt()), 4, 4)
        assertEquals(16, stats.clipped)
        assertEquals(16, stats.burnedWhite)
        assertEquals(16, stats.total)
        assertEquals(1f, stats.fraction, 0f)
        assertTrue(stats.blown)
    }

    @Test
    fun `gris moyen - ni ecrase ni compte`() {
        val stats = Clipping.stats(crop(0xFF808080.toInt()), 4, 4)
        assertEquals(0, stats.clipped)
        assertEquals(0f, stats.fraction, 0f)
        assertFalse(stats.blown)
    }

    @Test
    fun `vert sature - ecrete mais garde sa teinte`() {
        val stats = Clipping.stats(crop(0xFF00FF00.toInt()), 4, 4)
        assertEquals(16, stats.clipped)
        assertEquals(0, stats.burnedWhite)
        assertFalse(stats.blown)
    }

    @Test
    fun `noir - rien`() {
        val stats = Clipping.stats(crop(0xFF000000.toInt()), 4, 4)
        assertEquals(0, stats.clipped)
        assertEquals(0, stats.burnedWhite)
        assertEquals(0f, stats.fraction, 0f)
        assertFalse(stats.blown)
    }

    @Test
    fun `blanc teinte tres pale - saturation sous le plancher donc perdu`() {
        // canal max 255, min 200 : saturation 55/255 = 0,216 → encore coloré, pas écrasé.
        val colored = Clipping.stats(crop(0xFFC8FFFF.toInt()), 2, 2)
        assertEquals(4, colored.clipped)
        assertEquals(0, colored.burnedWhite)
        // canal max 255, min 250 : saturation 0,0196 → blanc, perdu pour le classifieur.
        val burned = Clipping.stats(crop(0xFFFFFFFA.toInt()), 2, 2)
        assertEquals(4, burned.burnedWhite)
        assertTrue(burned.blown)
    }

    @Test
    fun `melange - fraction du crop, pas du nombre de pixels ecrases`() {
        val pixels = intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF102030.toInt(), 0xFF203040.toInt())
        val stats = Clipping.stats(pixels, 2, 2)
        assertEquals(1, stats.burnedWhite)
        assertEquals(4, stats.total)
        assertEquals(0.25f, stats.fraction, 0.0001f)
        assertTrue("25 % du crop écrasé dépasse le seuil de 1,5 %", stats.blown)
    }

    @Test
    fun `mediane - garde-fou nocturne lu dans la meme passe`() {
        // Trois noirs et un blanc : médiane des canaux max = 0 (le plancher nocturne tire).
        val sombre = Clipping.stats(intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 2, 2)
        assertEquals(0, sombre.median)
        // Deux gris 0x40 et deux gris 0x80 : médiane 0x40 = 64, au-dessus du plancher.
        val moyen = Clipping.stats(intArrayOf(0xFF404040.toInt(), 0xFF404040.toInt(), 0xFF808080.toInt(), 0xFF808080.toInt()), 2, 2)
        assertEquals(0x40, moyen.median)
        // Un crop non mesuré ne propose aucun plancher (sentinel -1), jamais « noir ».
        assertEquals(ClipStats.UNMEASURED, ClipStats.NONE.median)
    }

    @Test
    fun `dimensions incoherentes - aucune mesure`() {
        assertEquals(ClipStats.NONE, Clipping.stats(IntArray(4), 3, 3))
        assertEquals(ClipStats.NONE, Clipping.stats(IntArray(4), 0, 0))
        assertEquals(0f, ClipStats.NONE.fraction, 0f)
        assertFalse(ClipStats.NONE.blown)
    }
}
