package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux pilote TTS système : enregistre les paroles. */
private class FakeCurbDriver : TtsDriver {
    val calls = mutableListOf<String>()
    override val speaking: Boolean get() = false

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float): Boolean {
        calls += text
        return true
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/**
 * Parole des guetteurs : bordure (imminente comme pré-alerte), mobilier et
 * véhicules parlent même en Traversée — une alerte de sécurité ne se tait
 * jamais pour une autre phrase ; les détections sont reconstruites des
 * lectures ; P1/P2 arbitrent le poteau.
 */
class ProbeWiringWatchersTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private class Setup(
        val scope: CoroutineScope,
        val probe: LiveProbe,
        val voice: ProximityVoice,
        val spoken: MutableList<String>,
    )

    private fun tempDir(): File = Files.createTempDirectory("watchers").toFile()

    /** Sonde + voix sur temps virtuel partagé, scope annulé en fin de test. */
    private fun TestScope.withSetup(block: Setup.() -> Unit) {
        ProbeBackgroundMotion.reset()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val spoken = mutableListOf<String>()
            val probe = LiveProbe(
                scope = this,
                clock = { 0L },
                background = StandardTestDispatcher(testScheduler),
            )
            val voice = ProximityVoice(
                player = SpeechPlayer(tempDir()),
                settings = { Settings.Default.copy(ttsApiKey = "cle-test") },
                onSpeaking = { if (it.isNotEmpty()) spoken += it },
                synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
            )
            Setup(scope, probe, voice, spoken).block()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `pre-alerte bordure parle en traversee, sans alerte de securite`() = runTest {
        withSetup {
            val safetyDriver = FakeCurbDriver()
            val safety = SafetyVoice(
                SpeechPlayer(tempDir()),
                SystemTtsEngine(safetyDriver),
                { Settings.Default },
            )
            probe.observeCurb(scope, voice, { true }, { SpeechLanguage.Fr }, safety)
            repeat(3) { i ->
                probe._state.value = LiveProbeState(
                    width = 512,
                    height = 1138,
                    frames = i,
                    crossingActive = true,
                    distanceLayer = DistanceLayer(
                        512, 1138,
                        // Bas décollé des pieds (1000/1138 < 0,92) : montée franche,
                        // pas la zone grise des plans plein cadre (« Attention bordure »).
                        listOf(reading("sidewalk", 1.0f, listOf(156f, 700f, 356f, 1000f))),
                    ),
                )
                advanceUntilIdle()
            }

            assertTrue(spoken.any { it.contains("Marche") })
            assertTrue(safetyDriver.calls.isEmpty())
        }
    }

    @Test
    fun `mobilier parle en traversee`() = runTest {
        withSetup {
            probe.observeHazard(scope, voice, { true }, { SpeechLanguage.Fr })
            repeat(3) { i ->
                probe._state.value = LiveProbeState(
                    width = 512,
                    height = 1138,
                    frames = i,
                    crossingActive = true,
                    distanceLayer = DistanceLayer(
                        512, 1138,
                        listOf(reading("barrier", 0.8f, listOf(156f, 400f, 356f, 900f))),
                    ),
                )
                advanceUntilIdle()
            }

            assertTrue(spoken.any { it.contains("barrière") })
        }
    }

    @Test
    fun `vehicules en mouvement parlent en traversee`() = runTest {
        withSetup {
            probe.observeVehicleState(scope, voice, { true }, { SpeechLanguage.En })
            listOf(100f, 110f, 120f, 130f).forEachIndexed { i, widthPx ->
                val heightPx = widthPx * 0.6f
                val box = listOf(
                    320f - widthPx / 2f, 240f - heightPx / 2f,
                    320f + widthPx / 2f, 240f + heightPx / 2f,
                )
                probe._state.value = LiveProbeState(
                    width = 640,
                    height = 480,
                    frames = i,
                    crossingActive = true,
                    distanceLayer = DistanceLayer(640, 480, listOf(reading("car", 5f, box))),
                )
                advanceUntilIdle()
            }

            assertTrue(spoken.any { it.contains("moving") })
        }
    }

    @Test
    fun `p1p2 recent = occupe, ancien = libre`() {
        assertTrue(p1p2Busy(nowMs = 5_000L, lastMovingAlertMs = 4_000L, lastNearMs = null))
        assertTrue(p1p2Busy(nowMs = 5_000L, lastMovingAlertMs = null, lastNearMs = 4_500L))
        assertFalse(p1p2Busy(nowMs = 9_000L, lastMovingAlertMs = 4_000L, lastNearMs = null))
        assertFalse(p1p2Busy(nowMs = 7_000L, lastMovingAlertMs = null, lastNearMs = 4_500L))
        assertFalse(p1p2Busy(nowMs = 5_000L, lastMovingAlertMs = null, lastNearMs = null))
    }

    @Test
    fun `poteau coupe seulement si imminent et p1p2 silencieux`() {
        assertTrue(shouldCutForHazard(cueUrgent = true, busy = false))
        assertFalse(shouldCutForHazard(cueUrgent = true, busy = true))
        assertFalse(shouldCutForHazard(cueUrgent = false, busy = false))
    }

    @Test
    fun `detections reconstruites des lectures, boites illisibles filtrees`() {
        val readings = listOf(
            DistanceReading(1, "car", 5f, 5f, listOf(10f, 20f, 110f, 80f)),
            DistanceReading(2, "bicycle", 2f, 2f, listOf(1f, 2f)),
        )

        val detections = detectionsOf(readings, width = 640, height = 480)

        assertEquals(1, detections.size)
        assertEquals("car", detections.single().label)
        assertEquals(1f, detections.single().score, 0f)
        assertEquals(listOf(10f, 20f, 110f, 80f), detections.single().box)
        assertEquals(640, detections.single().width)
        assertEquals(480, detections.single().height)
    }

    @Test
    fun `urgence parlee = stop ou alerte mouvement traversee`() {
        assertTrue(spokenUrgentOf("Stop."))
        assertTrue(spokenUrgentOf("obstacle, stop."))
        assertTrue(spokenUrgentOf("Stop, car moving."))
        assertFalse(spokenUrgentOf("Car moving, 5 meters away."))
        assertFalse(spokenUrgentOf("left."))
    }
}
