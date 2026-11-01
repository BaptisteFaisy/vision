package dev.sam3vision.app.isee

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * États moteurs du direct téléphone : mouvement et arrêt doux, publiés dans
 * l'état (sous-titre) puis lus — jamais de « Stop » hors traversée, et silence
 * total en traversée, pendant une question, ou réglage coupé.
 */
class LiveVehicleVoiceTest {

    private val state = MutableStateFlow(LiveUiState())
    private val spoken = mutableListOf<String>()
    private val configured = Settings.Default.copy(
        ttsApiKey = "cle-tts",
        ttsVoiceId = "voix",
        speechLanguage = SpeechLanguage.En,
    )

    private fun voice(
        scope: TestScope,
        traverse: Boolean = false,
        asking: Boolean = false,
        settings: Settings = configured,
    ) = LiveVehicleVoice(
        scope = scope,
        state = state,
        settings = { settings },
        traverseActive = { traverse },
        asking = { asking },
        speak = { _, phrase -> spoken += phrase },
    )

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    private fun stoppedCar(): Pair<List<Detection>, List<DistanceReading>> {
        val frame = box(100f)
        return listOf(det("car", frame)) to listOf(reading("car", 2f, frame))
    }

    @Test
    fun `l arret est publie dans l etat puis lu`() = runTest {
        val v = voice(this)
        val (detections, readings) = stoppedCar()

        v.announce(detections, readings, 0L)
        v.announce(detections, readings, 100L)
        assertNull("confirmation : silence", state.value.spoken)
        v.announce(detections, readings, 200L)

        val phrase = state.value.spoken
        assertNotNull("l'arrêt doit s'afficher en sous-titre", phrase)
        assertEquals("Car stopped, 2 meters away.", phrase)
        advanceUntilIdle()
        assertEquals(listOf(phrase), spoken)
    }

    @Test
    fun `le mouvement proche reste doux, jamais de stop hors traversee`() = runTest {
        val v = voice(this)
        val at = { width: Float, ms: Long ->
            val frame = box(width)
            v.announce(
                listOf(det("car", frame)),
                listOf(reading("car", 5f, frame)),
                ms,
            )
        }

        at(100f, 0L)
        at(110f, 100L)
        at(120f, 200L)

        assertEquals("Car moving.", state.value.spoken)
        advanceUntilIdle()
        assertEquals(listOf("Car moving."), spoken)
    }

    @Test
    fun `silence en traversee, la voix rouge-vert est proprietaire`() = runTest {
        val v = voice(this, traverse = true)
        val (detections, readings) = stoppedCar()

        v.announce(detections, readings, 0L)
        v.announce(detections, readings, 100L)
        v.announce(detections, readings, 200L)
        advanceUntilIdle()

        assertNull(state.value.spoken)
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `silence pendant une question sur la memoire`() = runTest {
        val v = voice(this, asking = true)
        val (detections, readings) = stoppedCar()

        v.announce(detections, readings, 0L)
        v.announce(detections, readings, 100L)
        v.announce(detections, readings, 200L)
        advanceUntilIdle()

        assertNull(state.value.spoken)
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `silence sans annonce positions`() = runTest {
        val v = voice(this, settings = configured.copy(ttsAnnouncePositions = false))
        val (detections, readings) = stoppedCar()

        v.announce(detections, readings, 0L)
        v.announce(detections, readings, 100L)
        v.announce(detections, readings, 200L)
        advanceUntilIdle()

        assertNull(state.value.spoken)
        assertEquals(emptyList<String>(), spoken)
    }

    @Test
    fun `reset reannonce la scene`() = runTest {
        val v = voice(this)
        val (detections, readings) = stoppedCar()

        v.announce(detections, readings, 0L)
        v.announce(detections, readings, 100L)
        v.announce(detections, readings, 200L)
        assertEquals("Car stopped, 2 meters away.", state.value.spoken)
        v.reset()
        v.announce(detections, readings, 300L)
        v.announce(detections, readings, 400L)
        v.announce(detections, readings, 500L)

        assertEquals("Car stopped, 2 meters away.", state.value.spoken)
        advanceUntilIdle()
        assertEquals(2, spoken.size)
    }
}
