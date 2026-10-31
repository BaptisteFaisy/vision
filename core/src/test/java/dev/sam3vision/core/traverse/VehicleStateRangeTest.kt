package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portée du mouvement : 15 m par défaut, ouverte à l'infini en traversée
 * sans feu (ordre user du 31/10 — toute voiture en mouvement se dit, même
 * loin). L'arrêt garde 15 m dans tous les cas.
 */
class VehicleStateRangeTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `mouvement a 30 m - muet en portee 15 m`() {
        val watcher = VehicleStateWatcher()
        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            assertNull(
                watcher.update(
                    listOf(det("car", box(width))),
                    listOf(reading("car", 30f, box(width))),
                    atMs = index * 100L,
                ),
            )
        }
        assertFalse(watcher.movingThisFrame)
    }

    @Test
    fun `mouvement a 30 m - parle en portee ouverte`() {
        val watcher = VehicleStateWatcher()
        val range = Float.POSITIVE_INFINITY
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 30f, box(100f))), atMs = 0L, movingMeters = range))
        assertNull(watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 30f, box(110f))), atMs = 100L, movingMeters = range))
        val third = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 30f, box(120f))),
            atMs = 200L,
            movingMeters = range,
        )

        assertEquals("Car moving, 30 meters away.", third?.phrase)
        assertTrue(watcher.movingThisFrame)
    }

    @Test
    fun `arret a 30 m - muet meme en portee ouverte`() {
        val watcher = VehicleStateWatcher()
        repeat(3) { index ->
            assertNull(
                watcher.update(
                    listOf(det("car", box(100f))),
                    listOf(reading("car", 30f, box(100f))),
                    atMs = index * 100L,
                    movingMeters = Float.POSITIVE_INFINITY,
                ),
            )
        }
        assertFalse(watcher.movingThisFrame)
    }
}
