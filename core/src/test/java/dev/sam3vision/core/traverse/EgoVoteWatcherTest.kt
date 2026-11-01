package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Séquences avec MOUVEMENT CAMÉRA (PISTE 1) : le vote fond vs objet fait taire
 * les faux mouvements (pan, suivi, zoom) sans tuer les vrais (approche sur
 * fond immobile prouvé). Synthétiques, miroir de `VehicleStateBackgroundTest`.
 */
class EgoVoteWatcherTest {

    @Test
    fun `pan + voiture garee - arret compense annonce, camera accusee`() {
        // Boîte à largeur constante qui suit le fond : ni croissance ni tau ;
        // la dérive jugée contre le fond prouve l'arrêt (compensé, pas un
        // faux) — et la caméra est accusée.
        val motion = FrameMotion(MotionSample(0.75f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        repeat(12) { index ->
            val frame = box(100f, cx = 320f + index * 5f)
            val cue = watcher.update(listOf(det("car", frame)), listOf(reading("car", 2f, frame)),
                atMs = index * 100L, motion = motion)
            if (index == 2) assertEquals("Car stopped, 2 meters away.", cue!!.phrase)
            else assertNull("pan suivi : confirmation puis cooldown", cue)
        }

        assertTrue(watcher.cameraMoved)
        assertNotNull(watcher.lastAlertMs)
    }

    @Test
    fun `fond immobile prouve + approche - mobile, urgent au centre`() {
        val motion = FrameMotion(MotionSample(0f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))),
            atMs = 0L, motion = motion))
        assertNull(watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))),
            atMs = 100L, motion = motion))
        val third = watcher.update(listOf(det("car", box(120f))), listOf(reading("car", 5f, box(120f))),
            atMs = 200L, language = SpeechLanguage.Fr, motion = motion)

        assertEquals("Voiture en mouvement, à 5 mètres.", third!!.phrase)
        assertTrue("tau + 5 m + centre : urgent", third.urgent)
    }

    @Test
    fun `pan suit voiture - ni mouvement ni faux arret`() {
        // Boîte fixe dans l'image pendant que le fond défile : le suivi
        // caméra coupe l'arrêt (sans la porte, la 3e frame annoncerait stoppée).
        val motion = FrameMotion(MotionSample(3f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        repeat(4) { index ->
            assertNull(
                "suivi caméra : ni mouvement ni arrêt",
                watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))),
                    atMs = index * 100L, motion = motion),
            )
        }

        assertTrue(watcher.cameraMoved)
        assertNull(watcher.lastAlertMs)
    }

    @Test
    fun `boite fixe sur fond fixe - l arret parle, controle du precedent`() {
        val motion = FrameMotion(MotionSample(0f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        assertNull(watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))),
            atMs = 0L, motion = motion))
        assertNull("confirmation en cours", watcher.update(listOf(det("car", box(100f))),
            listOf(reading("car", 2f, box(100f))), atMs = 100L, motion = motion))
        val third = watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))),
            atMs = 200L, language = SpeechLanguage.Fr, motion = motion)

        assertEquals("Voiture à l'arrêt, à 2 mètres.", third!!.phrase)
    }

    @Test
    fun `dilatation SAM une frame - silence, pas de faux positif`() {
        val watcher = VehicleStateWatcher()
        listOf(100f, 120f, 100f, 100f).forEachIndexed { index, width ->
            assertNull(
                "dilatation isolée : silence",
                watcher.update(listOf(det("car", box(width))), listOf(reading("car", 5f, box(width))),
                    atMs = index * 100L),
            )
        }

        assertNull(watcher.lastMovingAlertMs)
    }

    @Test
    fun `zoom fond + croissance lente - decotee, silence`() {
        // +1 %/frame sous zoom 2 %/frame, glissade latérale (piste jugée
        // roulante, pas tuée par le veto) : seule la décote fait taire. La
        // glissade (3 px/frame ≈ 4,7 %/s) reste entre le résidu franc
        // (2 %/s, sinon la piste suivrait le fond) et la traversée scan
        // (8 %/s, sinon le mouvement latéral parlerait légitimement).
        val motion = FrameMotion(MotionSample(0f, 0f, 1.02f, 0.9f), 256, 103, 100L)
        val muted = VehicleStateWatcher()
        val loud = VehicleStateWatcher()
        repeat(5) { index ->
            val frame = box(100f + index, cx = 320f + index * 3f)
            val atMs = index * 100L
            assertNull(
                "croissance expliquée par le zoom : silence",
                muted.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)),
                    atMs = atMs, motion = motion),
            )
            val spoke = loud.update(listOf(det("car", frame)), listOf(reading("car", 5f, frame)), atMs = atMs)
            if (index == 2) assertNotNull("sans fond, la croissance parle", spoke)
        }

        assertNull(muted.lastMovingAlertMs)
    }

    @Test
    fun `zoom fond - le tau decote se tait, controle approche`() {
        // Glissade + croissance franche (tau < 3 s) : sans fond ça approche,
        // sous zoom qui l'explique le tau se tait — verdict roulant, pas tu.
        val motion = FrameMotion(MotionSample(0f, 0f, 1.08f, 0.5f), 256, 103, 200L)
        val loud = VehicleApproach()
        assertFalse(loud.update(listOf(car(100f, 320f)), atMs = 0L).approaching)
        assertFalse(loud.update(listOf(car(120f, 350f)), atMs = 200L).approaching)
        val third = loud.update(listOf(car(140f, 380f)), atMs = 400L)
        assertTrue("tau ~2,4 s : le contrôle approche", third.approaching)

        val muted = VehicleApproach()
        assertFalse(muted.update(listOf(car(100f, 320f)), atMs = 0L, motion = motion).approaching)
        assertFalse(muted.update(listOf(car(120f, 350f)), atMs = 200L, motion = motion).approaching)
        assertFalse("zoom 8 % : le tau est décoté",
            muted.update(listOf(car(140f, 380f)), atMs = 400L, motion = motion).approaching)
        assertEquals(Verdict.VEHICLE_MOVED, muted.lastVerdicts.values.first().verdict)
    }

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    private fun car(widthPx: Float, cx: Float): Detection = det("car", box(widthPx, cx))
}
