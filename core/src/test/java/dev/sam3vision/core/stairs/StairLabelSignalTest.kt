package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.DistanceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escalier nommé par SAM : détecté, sens par le libellé puis la hauteur de boîte. */
class StairLabelSignalTest {

    private fun reading(label: String, y2: Float, height: Int = 100, meters: Float = 2f) =
        DistanceReading(1, label, meters, meters, listOf(10f, 10f, 90f, y2))

    @Test
    fun `sans libelle escalier, rien`() {
        val signal = StairLabelSignals.of(listOf(reading("chaise", 50f)), 100, 100)

        assertFalse(signal.detected)
        assertEquals(StairDirection.Unknown, signal.direction)
    }

    @Test
    fun `le libelle dit le sens en priorite`() {
        val up = StairLabelSignals.of(listOf(reading("stairs going up", 95f)), 100, 100)
        val down = StairLabelSignals.of(listOf(reading("escalier descendant", 40f)), 100, 100)

        assertTrue(up.detected)
        assertEquals(StairDirection.Up, up.direction)
        assertEquals(0.5f, up.centerX!!, 0.001f)
        assertTrue(down.detected)
        assertEquals(StairDirection.Down, down.direction)
    }

    @Test
    fun `boite haute sans mot de sens = montee`() {
        val signal = StairLabelSignals.of(listOf(reading("stairs", 50f)), 100, 100)

        assertTrue(signal.detected)
        assertEquals(StairDirection.Up, signal.direction)
    }

    @Test
    fun `boite qui touche le bas ne tranche pas`() {
        val signal = StairLabelSignals.of(listOf(reading("escalier", 95f)), 100, 100)

        assertTrue(signal.detected)
        assertEquals(StairDirection.Unknown, signal.direction)
    }

    @Test
    fun `image inconnue ou boite illisible = silence sur`() {
        assertFalse(StairLabelSignals.of(listOf(reading("stairs", 50f)), 0, 100).detected)
        val short = DistanceReading(1, "stairs", 2f, 2f, listOf(1f))
        assertFalse(StairLabelSignals.of(listOf(short), 100, 100).detected)
    }
}
