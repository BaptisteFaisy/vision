package dev.sam3vision.app.isee

import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Détections + distances d'une frame SAM : chaque objet segmenté part à la
 * voix (score 1, comme l'écran), mesuré ou non — une voiture vue est dite.
 */
class ProbeFrameTest {

    private fun frame(objects: List<SegObject>, width: Int = 640, height: Int = 480) =
        LiveFrame(width, height, elapsedMs = 100L, objects = objects)

    @Test
    fun `voiture confirmee score 1 et mesuree`() {
        val car = SegObject("car", 0.4f, listOf(270f, 200f, 370f, 280f), maskPngB64 = "")
        val (detections, readings) = detectionsAndReadingsOf(DistanceMeter(), frame(listOf(car)))

        assertEquals(1, detections.size)
        assertEquals(1f, detections.single().score, 0f)
        assertEquals("car", detections.single().label)
        assertEquals(1, readings.size)
    }

    @Test
    fun `frame vide ou sans dimensions - rien`() {
        val (emptyDetections, emptyReadings) = detectionsAndReadingsOf(DistanceMeter(), frame(emptyList()))
        assertTrue(emptyDetections.isEmpty())
        assertTrue(emptyReadings.isEmpty())

        val car = SegObject("car", 0.9f, listOf(270f, 200f, 370f, 280f), maskPngB64 = "")
        val (noSizeDetections, noSizeReadings) =
            detectionsAndReadingsOf(DistanceMeter(), frame(listOf(car), width = 0, height = 0))
        assertTrue(noSizeDetections.isEmpty())
        assertTrue(noSizeReadings.isEmpty())
    }
}
