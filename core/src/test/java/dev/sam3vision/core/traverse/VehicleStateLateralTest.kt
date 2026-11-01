package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * États véhicules, mouvement latéral : la traversée du champ à taille
 * stable est annoncée comme mouvement (le tau ne la voit pas), et le brut
 * par frame ([VehicleStateWatcher.movingThisFrame], lu par le scan
 * traversée) est vrai avant l'annonce. Séparé de `VehicleStateTest`
 * (plafond 500 lignes du garde-fou). Séquences synthétiques.
 */
class VehicleStateLateralTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `traversee laterale - mouvement annonce, brut des la 3e frame`() {
        val watcher = VehicleStateWatcher()
        watcher.update(listOf(det("car", box(100f, cx = 200f))), listOf(reading("car", 5f, box(100f, cx = 200f))), atMs = 0L)
        assertFalse(watcher.movingThisFrame)
        watcher.update(listOf(det("car", box(100f, cx = 230f))), listOf(reading("car", 5f, box(100f, cx = 230f))), atMs = 100L)
        assertFalse("vitesse pas encore établie", watcher.movingThisFrame)
        assertNull(watcher.update(listOf(det("car", box(100f, cx = 260f))), listOf(reading("car", 5f, box(100f, cx = 260f))), atMs = 200L))
        assertTrue("latéral confirmé : brut vrai avant l'annonce", watcher.movingThisFrame)
        val fourth = watcher.update(
            listOf(det("car", box(100f, cx = 290f))),
            listOf(reading("car", 5f, box(100f, cx = 290f))),
            atMs = 300L,
            language = SpeechLanguage.Fr,
        )

        assertEquals("Voiture.", fourth!!.phrase)
    }

    @Test
    fun `brut par frame - vrai en croissance, faux gare, reset efface`() {
        val watcher = VehicleStateWatcher()
        assertFalse("aucune frame vue", watcher.movingThisFrame)
        watcher.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), atMs = 0L)
        assertFalse(watcher.movingThisFrame)
        watcher.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), atMs = 100L)
        assertTrue("croissance : brut vrai avant l'annonce", watcher.movingThisFrame)

        val parked = VehicleStateWatcher()
        repeat(3) { index ->
            parked.update(listOf(det("car", box(100f))), listOf(reading("car", 2f, box(100f))), atMs = index * 100L)
        }
        assertFalse("arrêt : brut faux", parked.movingThisFrame)

        watcher.reset()
        assertFalse("reset : brut effacé", watcher.movingThisFrame)
    }

    @Test
    fun `voiture qui traverse - mouvement lateral annonce`() {
        // Fond fixe (caméra immobile) + boîte qui glisse à taille stable :
        // la traversée latérale (verdict approche) annonce dès confirmation.
        val motion = FrameMotion(MotionSample(0f, 0f, 1f, 0.9f), 96, 96, 100L)
        val watcher = VehicleStateWatcher()
        val frames = listOf(200f, 240f, 280f, 320f, 360f).map { box(100f, cx = it) }
        frames.forEachIndexed { index, frame ->
            val cue = watcher.update(
                listOf(det("car", frame)), listOf(reading("car", 12f, frame)),
                atMs = index * 100L, language = SpeechLanguage.Fr, motion = motion,
            )
            if (index < 3) assertNull(cue)
            else if (index == 3) assertEquals("Voiture.", cue!!.phrase)
            else assertNull("cooldown mouvement", cue)
        }
    }

    @Test
    fun `churn lateral - la garee d a cote n est pas comptee mobile (attribution)`() {
        // Une voiture qui glisse vite (latéral franc, label « car ») à côté
        // d'une garée : sans attribution, le latéral s'impute aux deux (label
        // seul) et la voix compte 2 mobiles ; avec, seule la glissante parle.
        val watcher = VehicleStateWatcher()
        val parkedBox = box(120f, cx = 500f)
        var cue = watcher.update(
            listOf(det("car", box(100f, cx = 60f)), det("car", parkedBox)),
            listOf(reading("car", 12f, box(100f, cx = 60f)), reading("car", 5f, parkedBox)),
            atMs = 0L, language = SpeechLanguage.Fr,
        )
        assertNull(cue)
        val centers = listOf(100f, 140f, 180f)
        centers.forEachIndexed { index, cx ->
            val driftBox = box(100f, cx = cx)
            cue = watcher.update(
                listOf(det("car", driftBox), det("car", parkedBox)),
                listOf(reading("car", 12f, driftBox), reading("car", 5f, parkedBox)),
                atMs = (index + 1) * 100L, language = SpeechLanguage.Fr,
            )
            if (index < 2) assertNull(cue) else {
                assertEquals("seule la glissante est mobile", "Voiture.", cue!!.phrase)
                assertEquals("la garée n'est pas comptée", 1, cue!!.movingCount)
                assertTrue(cue!!.moving)
            }
        }
    }
}
