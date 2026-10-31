package dev.sam3vision.app.isee

import dev.sam3vision.core.stairs.StairDirection
import dev.sam3vision.core.stairs.StairLabelSignal
import dev.sam3vision.core.stairs.StairRoi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * ROI du compteur dans la boîte SAM : boîte pixels → normalisée 0..1 ;
 * `null` (balayage entier) sans escalier localisé, sans boîte ou dégénérée.
 */
class ProbeStairsRoiTest {

    @Test
    fun `boite pixels normalisee`() {
        val found = StairLabelSignal(
            detected = true, direction = StairDirection.Up, centerX = 0.5f,
            box = listOf(10f, 10f, 90f, 95f),
        )

        assertEquals(StairRoi(0.1f, 0.1f, 0.9f, 0.95f), stairsRoiOf(found, 100, 100))
    }

    @Test
    fun `sans boite ni detection ni dimensions = null`() {
        val labelsOnly = StairLabelSignal(detected = true, direction = StairDirection.Unknown)
        val silent = StairLabelSignal.none()
        val boxed = StairLabelSignal(
            detected = true, direction = StairDirection.Up, box = listOf(10f, 10f, 90f, 95f))

        assertNull(stairsRoiOf(labelsOnly, 100, 100))
        assertNull(stairsRoiOf(silent, 100, 100))
        assertNull(stairsRoiOf(boxed, 0, 100))
        assertNull(stairsRoiOf(boxed, 100, 0))
    }

    @Test
    fun `boite degeneree = null`() {
        val flat = StairLabelSignal(
            detected = true, direction = StairDirection.Up, box = listOf(50f, 10f, 50f, 95f))

        assertNull(stairsRoiOf(flat, 100, 100))
    }
}
