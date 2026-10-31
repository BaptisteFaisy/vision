package dev.sam3vision.core.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Confirmation d'approche par la profondeur : l'embarqué prime, le serveur
 * dépanne, la hausse prouve l'approche, le pallier la tait, et sans mesure
 * la géométrie décide comme avant. JUnit seul.
 */
class DepthConfirmTest {

    @Test
    fun `resolve - l embarque prime sur le serveur`() {
        assertEquals(0.7f, DepthConfirm.resolve(embedded = 0.7f, server = 0.2f)!!, 0f)
    }

    @Test
    fun `resolve - repli serveur quand l embarque est muet`() {
        assertEquals(0.2f, DepthConfirm.resolve(embedded = null, server = 0.2f)!!, 0f)
        assertEquals(0.2f, DepthConfirm.resolve(embedded = Float.NaN, server = 0.2f)!!, 0f)
    }

    @Test
    fun `resolve - rien de fini, rien de mesure`() {
        assertNull(DepthConfirm.resolve(null, null))
        assertNull(DepthConfirm.resolve(Float.NaN, Float.POSITIVE_INFINITY))
    }

    @Test
    fun `hausse franche - l approche est confirmee`() {
        assertTrue(DepthConfirm.rises(0.4f, 0.6f)!!)
        assertTrue(DepthConfirm.confirmsGrowth(0.4f, 0.6f))
        assertFalse(DepthConfirm.contradicts(0.4f, 0.6f))
    }

    @Test
    fun `proximite constante - silence, c est un pan ou un zoom`() {
        assertFalse(DepthConfirm.rises(0.5f, 0.5f)!!)
        assertFalse(DepthConfirm.confirmsGrowth(0.5f, 0.5f))
        assertTrue(DepthConfirm.contradicts(0.5f, 0.5f))
    }

    @Test
    fun `proximite en baisse - silence aussi`() {
        assertFalse(DepthConfirm.rises(0.6f, 0.4f)!!)
        assertTrue(DepthConfirm.contradicts(0.6f, 0.4f))
    }

    @Test
    fun `sans mesure - le doute ne tait jamais (repli geometrie)`() {
        assertNull(DepthConfirm.rises(null, 0.5f))
        assertNull(DepthConfirm.rises(0.5f, null))
        assertNull(DepthConfirm.rises(Float.NaN, 0.5f))
        assertFalse(DepthConfirm.contradicts(null, 0.5f))
        assertTrue(DepthConfirm.confirmsGrowth(null, 0.5f))
    }

    @Test
    fun `carte brute - le seuil relatif suit l echelle`() {
        // 2 % de 100 = 2 : +3 prouve, +1 ne prouve pas.
        assertTrue(DepthConfirm.rises(100f, 103f)!!)
        assertFalse(DepthConfirm.rises(100f, 101f)!!)
    }

    @Test
    fun `plancher incalculable - indecidable`() {
        assertNull(DepthConfirm.rises(Float.MAX_VALUE, Float.MAX_VALUE, minRiseRel = 2f))
    }
}
