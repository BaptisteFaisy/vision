package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veilleur état sous signal fond V1 : la dérive qui suit la caméra prouve
 * l'arrêt (compensée), la croissance sous caméra ne parle jamais en mouvement,
 * annonce maintenue pour les clés jeunes jamais jugées. Séquences
 * synthétiques, miroir de `VehicleStateTest`.
 */
class VehicleStateBackgroundTest {

    @Test
    fun `camera qui suit - arret compense annonce, mouvement tu`() {
        // Dérive qui suit le fond, puis croissance : sans fond la croissance
        // annoncerait du mouvement ; avec fond, la dérive compensée prouve
        // l'arrêt — et la croissance sous caméra se tait toujours.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val muted = VehicleStateWatcher()
        val loud = VehicleStateWatcher()
        var stopped: VehicleStateCue? = null
        repeat(12) { index ->
            val width = if (index < 8) 100f else 100f + (index - 7) * 10f
            val frame = box(width, cx = 320f + index * 5f)
            val atMs = index * 100L
            val hushed = muted.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)), atMs = atMs, motion = motion)
            val spoke = loud.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)), atMs = atMs)
            if (index == 2) stopped = hushed
            if (index >= 8) assertNull("croissance sous caméra : pas de mouvement", hushed)
            if (index == 9) assertNotNull("sans fond, la croissance parle", spoke)
        }

        assertEquals("Car stopped, 5 meters away.", stopped!!.phrase)
        assertTrue(muted.cameraMoved)
        assertNull(muted.lastMovingAlertMs)
    }

    @Test
    fun `camera qui suit - jeune cle non jugee, annonce maintenue`() {
        // Voiture tue par la caméra + bus qui apparaît en croissance : la clé
        // bus, jamais évaluée, annonce à la confirmation malgré la caméra.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        var last: VehicleStateCue? = null
        repeat(13) { index ->
            val carWidth = if (index < 8) 100f else 100f + (index - 7) * 10f
            val carBox = box(carWidth, cx = 320f + index * 5f)
            val detections = mutableListOf(det("car", carBox))
            val readings = mutableListOf(reading("car", 5f, carBox))
            if (index >= 10) {
                val busBox = box(100f + (index - 10) * 10f, cx = 500f)
                detections += det("bus", busBox)
                readings += reading("bus", 3f, busBox)
            }
            last = watcher.update(detections, readings, atMs = index * 100L, motion = motion)
            if (index in 8..11) assertNull("voiture tue, bus pas encore confirmé", last)
        }

        assertNotNull("le jeune bus en croissance annonce", last)
        assertEquals(3f, last!!.meters)
    }

    @Test
    fun `glissade laterale sans croissance - silence`() {
        // V1 (signal fond absent) : sans fond, une boîte qui glisse à largeur
        // constante n'est ni mouvement (ni tau ni croissance) ni arrêt.
        val bare = VehicleStateWatcher()
        listOf(300f, 320f, 340f).forEachIndexed { index, cx ->
            assertNull(
                "ni tau ni croissance : silence",
                bare.update(listOf(det("car", box(100f, cx = cx))), emptyList(), atMs = index * 100L),
            )
        }

        val near = VehicleStateWatcher()
        listOf(300f, 320f, 340f).forEachIndexed { index, cx ->
            assertNull(
                "dérive trop vive pour un arrêt : silence",
                near.update(
                    listOf(det("car", box(100f, cx = cx))),
                    listOf(reading("car", 2f, box(100f, cx = cx))),
                    atMs = index * 100L,
                ),
            )
        }
    }

    @Test
    fun `mouvement à 8 m pile - stop, frontiere proche`() {
        // V1 (signal fond absent) : la frontière proche (≤ 8 m) parle en stop.
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 8f, box(100f))), atMs = 0L)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 8f, box(110f))), atMs = 100L)
        val third = watcher.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 8f, box(120f))),
            atMs = 200L,
            language = SpeechLanguage.En,
        )

        assertEquals("Car moving, 8 meters away.", third!!.phrase)
        assertEquals("Stop, car moving.", third.safetyPhrase)
        assertTrue("tau + 8 m + centre : urgent", third.urgent)
    }

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `still compense - derive qui suit la camera, arret annonce`() {
        // Caméra qui avance (+5 px/frame comme le fond) : sans fond, silence ; avec fond,
        // la dérive compensée prouve l'arrêt (5 m, seau stable) — jamais du mouvement.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val bare = VehicleStateWatcher()
        repeat(3) { index ->
            val frame = box(100f, cx = 320f + index * 5f)
            assertNull(bare.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)), atMs = index * 100L))
        }
        val watcher = VehicleStateWatcher()
        var cue: VehicleStateCue? = null
        repeat(3) { index ->
            val frame = box(100f, cx = 320f + index * 5f)
            cue = watcher.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)), atMs = index * 100L, motion = motion)
        }

        assertEquals("Car stopped, 5 meters away.", cue!!.phrase)
        assertNull("jamais de mouvement : pas d'alerte", watcher.lastMovingAlertMs)
    }
}
