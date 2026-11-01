package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.GyroHint
import dev.sam3vision.core.motion.GyroMotion
import dev.sam3vision.core.motion.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification mouvement-arret robuste au mouvement de camera (gyro-arret) :
 * la rotation gyro, soustraite du mouvement apparent de chaque piste quand le
 * fond optique est muet, prouve l'arret compense sans jamais faire passer un
 * mobile pour arrete. Dehors (spec v4) : mouvement seul, seuil 20 m.
 */
class VehicleGyroStillTest {

    companion object {
        /** Pan gyro : 0,75 px luma / 100 ms sur 96 px = la glissade test (5 px / 100 ms sur 640). */
        private fun panGyro() = GyroMotion(GyroHint(0.75f, 0f, 0.9f), 96, 96, 100L)

        private fun stillGyro() = GyroMotion(GyroHint(0f, 0f, 0.9f), 96, 96, 100L)

        private fun panMotion() = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
    }

    @Test
    fun `camera mobile gyro seul - voiture garee qui suit, arretee`() {
        // Pan pur (largeur constante) sous gyro seul : la derive compensee
        // prouve l'arret, puis la croissance sous camera hue se tait.
        val watcher = VehicleStateWatcher()
        var stopped: VehicleStateCue? = null
        repeat(12) { index ->
            val width = if (index < 8) 100f else 100f + (index - 7) * 10f
            val frame = box(width, cx = 320f + index * 5f)
            val cue = watcher.update(
                listOf(det("car", frame)), listOf(reading("car", 5f, frame)),
                atMs = index * 100L, gyro = panGyro(),
            )
            if (index == 2) stopped = cue
            if (index >= 8) assertNull("croissance sous camera gyro : pas de mouvement", cue)
        }

        assertEquals("Car stopped, 5 meters away.", stopped!!.phrase)
        assertTrue(watcher.cameraMoved)
        assertFalse(watcher.movingThisFrame)
        assertNull(watcher.lastMovingAlertMs)
    }

    @Test
    fun `camera mobile gyro seul - voiture qui roule, mouvement`() {
        // Boite fixe qui gonfle pendant que le gyro defile : poursuite camera
        // d'un mobile — la croissance parle, jamais tue par le gyro.
        val watcher = VehicleStateWatcher()
        var moving: VehicleStateCue? = null
        repeat(3) { index ->
            val frame = box(100f + index * 10f, cx = 320f)
            moving = watcher.update(
                listOf(det("car", frame)), listOf(reading("car", 12f, frame)),
                atMs = index * 100L, gyro = panGyro(),
            )
        }

        assertEquals("Car moving.", moving!!.phrase)
        assertNull("12 m : doux, pas de stop", moving.safetyPhrase)
        assertTrue(watcher.movingThisFrame)
        assertTrue("camera prouvee mobile par le gyro, et le mobile parle quand meme", watcher.cameraMoved)
    }

    @Test
    fun `camera fixe - sans gyro, comportement historique inchange`() {
        val watcher = VehicleStateWatcher()
        var stopped: VehicleStateCue? = null
        repeat(3) { index ->
            stopped = watcher.update(
                listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))),
                atMs = index * 100L,
            )
        }

        assertEquals("Car stopped, 5 meters away.", stopped!!.phrase)
        assertFalse(watcher.movingThisFrame)
    }

    @Test
    fun `camera fixe - gyro nul et boite nulle, a l arret`() {
        val watcher = VehicleStateWatcher()
        var stopped: VehicleStateCue? = null
        repeat(3) { index ->
            stopped = watcher.update(
                listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))),
                atMs = index * 100L, gyro = stillGyro(),
            )
        }

        assertEquals("Car stopped, 5 meters away.", stopped!!.phrase)
        assertFalse(watcher.movingThisFrame)
    }

    @Test
    fun `visuel sur et gyro contradictoire - le visuel gagne`() {
        // Fond franc suivi par la boite + gyro nul : l'arret compense visuel
        // parle, le gyro ne le contredit pas.
        val watcher = VehicleStateWatcher()
        var stopped: VehicleStateCue? = null
        repeat(3) { index ->
            val frame = box(100f, cx = 320f + index * 5f)
            stopped = watcher.update(
                listOf(det("car", frame)), listOf(reading("car", 5f, frame)),
                atMs = index * 100L, motion = panMotion(), gyro = stillGyro(),
            )
        }

        assertEquals("Car stopped, 5 meters away.", stopped!!.phrase)
        assertTrue(watcher.cameraMoved)
    }

    @Test
    fun `dehors - arret et gare tus, mouvement parle`() {
        val stoppedWatcher = VehicleStateWatcher()
        repeat(3) { index ->
            assertNull(
                "dehors : l'arret se tait",
                stoppedWatcher.update(
                    listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))),
                    atMs = index * 100L, announceStopped = false,
                ),
            )
        }
        assertFalse(stoppedWatcher.movingThisFrame)

        val parkedWatcher = VehicleStateWatcher()
        repeat(3) { index ->
            assertNull(
                "dehors : le stationnement se tait",
                parkedWatcher.update(
                    listOf(det("car", box(100f))), listOf(reading("car", 10f, box(100f))),
                    atMs = index * 100L, announceStopped = false,
                ),
            )
        }

        val movingWatcher = VehicleStateWatcher()
        var moving: VehicleStateCue? = null
        repeat(3) { index ->
            val frame = box(100f + index * 10f)
            moving = movingWatcher.update(
                listOf(det("car", frame)), listOf(reading("car", 12f, frame)),
                atMs = index * 100L, announceStopped = false,
            )
        }
        assertEquals("Car moving.", moving!!.phrase)
    }

    @Test
    fun `dehors - seuil mouvement 20 m`() {
        assertEquals(20f, VehicleStateWatcher.OUTDOOR_MOVING_METERS, 0f)

        val near = VehicleStateWatcher()
        var spoke: VehicleStateCue? = null
        repeat(3) { index ->
            val frame = box(100f + index * 10f)
            spoke = near.update(
                listOf(det("car", frame)), listOf(reading("car", 18f, frame)),
                atMs = index * 100L, movingMeters = VehicleStateWatcher.OUTDOOR_MOVING_METERS,
            )
        }
        assertEquals("18 m <= 20 m : parle", "Car moving.", spoke!!.phrase)

        val far = VehicleStateWatcher()
        repeat(3) { index ->
            val frame = box(100f + index * 10f)
            assertNull(
                "21 m > 20 m : silence",
                far.update(
                    listOf(det("car", frame)), listOf(reading("car", 21f, frame)),
                    atMs = index * 100L, movingMeters = VehicleStateWatcher.OUTDOOR_MOVING_METERS,
                ),
            )
        }
        assertFalse(far.movingThisFrame)
    }

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }
}
