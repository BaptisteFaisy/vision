package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gigue pixel sans fond ni approche (S1) : une voiture garée dont la boîte
 * flicker de ±1-2 px ne conclut ni mouvement ni « Stop » — elle s'annonce à
 * l'arrêt. Miroir de `VehicleStateTest` (mêmes aides, fichier plein à 483/500).
 */
class VehicleStateNoiseTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `gigue pixel sans fond - a l arret, jamais stop`() {
        val watcher = VehicleStateWatcher()
        var said: VehicleStateCue? = null
        listOf(100f, 101f, 102f, 101f, 102f).forEachIndexed { index, width ->
            val cue = watcher.update(
                listOf(det("car", box(width))),
                listOf(reading("car", 3f, box(width))),
                atMs = index * 200L,
                language = SpeechLanguage.En,
            )
            if (said == null) said = cue
        }
        assertTrue("gigue : une annonce sort", said != null)
        assertFalse("gigue : pas en mouvement", said!!.moving)
        assertNull("gigue : pas de stop", said.safetyPhrase)
        assertEquals("Car stopped, 3 meters away.", said.phrase)
    }
}
