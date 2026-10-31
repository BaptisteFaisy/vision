package dev.sam3vision.core.stairs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Escalier nommé par SAM dans les libellés bruts, sans lecture de distance :
 * repli quand l'escalier n'a pas de mesure (ni hauteur connue, ni pose, ni
 * ancre de profondeur) — la voix compte quand même les marches vues.
 */
class StairLabelsFallbackTest {

    @Test
    fun `libelle montant = detecte, sans geometrie`() {
        val signal = StairLabelSignals.ofLabels(listOf("chaise", "stairs going up"))

        assertTrue(signal.detected)
        assertEquals(StairDirection.Up, signal.direction)
        assertNull(signal.centerX)
    }

    @Test
    fun `libelle descendant = detecte`() {
        val signal = StairLabelSignals.ofLabels(listOf("escalier descendant"))

        assertTrue(signal.detected)
        assertEquals(StairDirection.Down, signal.direction)
    }

    @Test
    fun `escalier sans sens = detecte sans sens`() {
        val plain = StairLabelSignals.ofLabels(listOf("stairs"))
        val marche = StairLabelSignals.ofLabels(listOf("marche"))

        assertTrue(plain.detected)
        assertEquals(StairDirection.Unknown, plain.direction)
        assertTrue(marche.detected)
        assertEquals(StairDirection.Unknown, marche.direction)
    }

    @Test
    fun `sans libelle escalier, rien`() {
        assertFalse(StairLabelSignals.ofLabels(listOf("chaise", "personne")).detected)
        assertFalse(StairLabelSignals.ofLabels(emptyList()).detected)
    }
}
