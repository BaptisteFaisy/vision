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
}
