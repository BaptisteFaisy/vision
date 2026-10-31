package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voix douce des véhicules moteurs en mode Traversée : en mouvement ou à
 * l'arrêt, dans la langue de la voix (anglais par défaut) — après le feu,
 * avant les personnes, et muette sur un ordre du feu nouveau.
 */
class CrossingVehicleTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    private fun red() = CrossingCue("Don't cross yet", false, CrossingState.Red)

    private fun stoppedCar(widthPx: Float = 100f): Pair<List<Detection>, List<DistanceReading>> {
        val frame = box(widthPx)
        return listOf(det("car", frame)) to listOf(reading("car", 2f, frame))
    }

    @Test
    fun `voiture proche en mouvement dit stop en traversee`() {
        CrossingVehicleWatch.reset()
        val language = SpeechLanguage.En

        assertNull(CrossingVehicleWatch.update(listOf(det("car", box(100f))), listOf(reading("car", 5f, box(100f))), 0L, language))
        assertNull(CrossingVehicleWatch.update(listOf(det("car", box(110f))), listOf(reading("car", 5f, box(110f))), 100L, language))
        val third = CrossingVehicleWatch.update(
            listOf(det("car", box(120f))),
            listOf(reading("car", 5f, box(120f))),
            200L,
            language,
        )

        assertEquals("Stop, car moving.", third)
    }

    @Test
    fun `voiture proche en mouvement dit stop en francais en traversee`() {
        CrossingVehicleWatch.reset()
        val at = { width: Float, ms: Long ->
            val frame = box(width)
            CrossingVehicleWatch.update(
                listOf(det("car", frame)),
                listOf(reading("car", 5f, frame)),
                ms,
                SpeechLanguage.Fr,
            )
        }

        assertNull(at(100f, 0L))
        assertNull(at(110f, 100L))
        assertEquals("Stop, voiture en mouvement.", at(120f, 200L))
    }

    @Test
    fun `voiture arretee annoncee en anglais a la 3e frame`() {
        CrossingVehicleWatch.reset()
        val (detections, readings) = stoppedCar()

        assertNull(CrossingVehicleWatch.update(detections, readings, 0L, SpeechLanguage.En))
        assertNull(CrossingVehicleWatch.update(detections, readings, 100L, SpeechLanguage.En))
        assertEquals(
            "Car stopped, 2 meters away.",
            CrossingVehicleWatch.update(detections, readings, 200L, SpeechLanguage.En),
        )
    }

    @Test
    fun `le feu prime sur les vehicules`() {
        CrossingVehicleWatch.reset()
        CrossingPeopleWatch.reset()
        val (detections, readings) = stoppedCar()
        val placed = listOf(PlacedLabel("person", centerX = 0.1f))
        val cue = CrossingCue("You can cross", false, CrossingState.Green)

        assertEquals(
            "You can cross",
            crossingPhraseOf(cue, placed, detections, readings, 0L, SpeechLanguage.En),
        )
    }

    @Test
    fun `les vehicules priment, puis les personnes se taisent`() {
        CrossingVehicleWatch.reset()
        CrossingPeopleWatch.reset()
        val (detections, readings) = stoppedCar()
        val placed = listOf(PlacedLabel("person", centerX = 0.1f))

        // Confirmation de l'arrêt hors phrase (2 frames).
        assertNull(CrossingVehicleWatch.update(detections, readings, 0L, SpeechLanguage.En))
        assertNull(CrossingVehicleWatch.update(detections, readings, 100L, SpeechLanguage.En))
        // 3e frame : l'arrêt parle, les personnes attendent.
        assertEquals(
            "Car stopped, 2 meters away.",
            crossingPhraseOf(null, placed, detections, readings, 200L, SpeechLanguage.En),
        )
        // 4e frame : l'arrêt est en cooldown, et les personnes ne se
        // décrivent plus en traversée — seuls les mots d'évitement parlent.
        assertNull(
            crossingPhraseOf(null, placed, detections, readings, 300L, SpeechLanguage.En),
        )
    }

    @Test
    fun `un ordre du feu nouveau fait taire puis rend la parole`() {
        CrossingVehicleWatch.reset()
        val (detections, readings) = stoppedCar()

        assertNull(CrossingVehicleWatch.update(detections, readings, 0L, SpeechLanguage.En))
        assertNull(CrossingVehicleWatch.update(detections, readings, 100L, SpeechLanguage.En))
        assertNull("le feu parle : silence", CrossingVehicleWatch.update(detections, readings, 200L, SpeechLanguage.En, red()))
        assertNull("ordre récent : silence", CrossingVehicleWatch.update(detections, readings, 300L, SpeechLanguage.En))
        // La confirmation a survécu à la pause : la parole rendue parle aussitôt.
        assertEquals(
            "Car stopped, 2 meters away.",
            CrossingVehicleWatch.update(detections, readings, 2700L, SpeechLanguage.En),
        )
    }

    @Test
    fun `cycles muets comme en normal`() {
        CrossingVehicleWatch.reset()

        listOf(100f, 110f, 120f).forEachIndexed { index, width ->
            val frame = box(width)
            assertNull(
                CrossingVehicleWatch.update(
                    listOf(det("bicycle", frame)),
                    listOf(reading("bicycle", 5f, frame)),
                    index * 100L,
                    SpeechLanguage.En,
                ),
            )
        }
    }

    @Test
    fun `reset reannonce la scene`() {
        CrossingVehicleWatch.reset()
        val (detections, readings) = stoppedCar()

        assertNull(CrossingVehicleWatch.update(detections, readings, 0L, SpeechLanguage.En))
        assertNull(CrossingVehicleWatch.update(detections, readings, 100L, SpeechLanguage.En))
        assertEquals(
            "Car stopped, 2 meters away.",
            CrossingVehicleWatch.update(detections, readings, 200L, SpeechLanguage.En),
        )
        CrossingVehicleWatch.reset()
        assertNull(CrossingVehicleWatch.update(detections, readings, 300L, SpeechLanguage.En))
        assertNull(CrossingVehicleWatch.update(detections, readings, 400L, SpeechLanguage.En))
        assertEquals(
            "Car stopped, 2 meters away.",
            CrossingVehicleWatch.update(detections, readings, 500L, SpeechLanguage.En),
        )
    }

    @Test
    fun `mouvement loin annonce en anglais en traversee`() {
        CrossingVehicleWatch.reset()
        val at = { width: Float, ms: Long ->
            val frame = box(width)
            CrossingVehicleWatch.update(
                listOf(det("car", frame)),
                listOf(reading("car", 12f, frame)),
                ms,
                SpeechLanguage.En,
            )
        }

        assertNull(at(100f, 0L))
        assertNull(at(110f, 100L))
        assertEquals("Car moving, 12 meters away.", at(120f, 200L))
    }

    @Test
    fun `mouvement a 30 m muet avec feu, parle sans feu`() {
        CrossingVehicleWatch.reset()
        val at = { width: Float, ms: Long, noLight: Boolean ->
            val frame = box(width)
            CrossingVehicleWatch.update(
                listOf(det("car", frame)),
                listOf(reading("car", 30f, frame)),
                ms,
                SpeechLanguage.En,
                noLight = noLight,
            )
        }

        assertNull(at(100f, 0L, false))
        assertNull(at(110f, 100L, false))
        assertNull(at(120f, 200L, false))

        CrossingVehicleWatch.reset()
        assertNull(at(100f, 0L, true))
        assertNull(at(110f, 100L, true))
        assertEquals("Car moving, 30 meters away.", at(120f, 200L, true))
    }
}
