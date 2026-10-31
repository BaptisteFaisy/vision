package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
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
    fun `camera qui avance sur voiture garee - silence avec fond, annonce sans fond`() {
        // Glissade + croissance d'une boîte garée à 30 m : sans fond ça
        // annonce (croissance), avec fond la caméra unanime fait taire.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val range = Float.POSITIVE_INFINITY
        val muted = VehicleStateWatcher()
        val loud = VehicleStateWatcher()
        repeat(12) { index ->
            val width = if (index < 8) 100f else 100f + (index - 7) * 10f
            val frame = box(width, cx = 320f + index * 5f)
            val atMs = index * 100L
            val hushed = muted.update(
                listOf(det("car", frame)), listOf(reading("car", 30f, frame)),
                atMs = atMs, motion = motion, movingMeters = range,
            )
            val spoke = loud.update(
                listOf(det("car", frame)), listOf(reading("car", 30f, frame)),
                atMs = atMs, movingMeters = range,
            )
            if (index >= 8) assertNull("caméra unanime : le mouvement se tait", hushed)
            if (index == 9) assertEquals("Car moving, 30 meters away.", spoke?.phrase)
        }

        assertTrue(muted.cameraMoved)
        assertFalse(muted.movingThisFrame)
    }

    @Test
    fun `pan sur voiture garee lointaine - silence`() {
        // Pan pur (largeur constante) sur une boîte garée à 30 m : aucun
        // mouvement conclu, le verdict reste libre de dire « Traversez. ».
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        repeat(12) { index ->
            val frame = box(100f, cx = 320f + index * 5f)
            assertNull(
                watcher.update(
                    listOf(det("car", frame)), listOf(reading("car", 30f, frame)),
                    atMs = index * 100L, motion = motion, movingMeters = Float.POSITIVE_INFINITY,
                ),
            )
        }

        assertTrue(watcher.cameraMoved)
        assertFalse(watcher.movingThisFrame)
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
