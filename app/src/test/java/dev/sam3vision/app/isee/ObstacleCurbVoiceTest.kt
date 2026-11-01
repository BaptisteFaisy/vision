package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateWatcher
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bout-en-bout bordure→voix : le vrai [obstacleFrame] branché sur des voix
 * capturantes — une bordure valide (label `curb`, ≤ 3 m, centre du couloir,
 * bas de boîte en bande basse) confirmée sur 2 frames parle en doux
 * (« Bordure détectée… ») sans alerte ; des lectures sans label bordure
 * restent muettes. La ligne diagnostique `CurbVoice` y résume la frame.
 */
class ObstacleCurbVoiceTest {

    /** Pilote TTS système capturant : la sécurité parle sans bruit dans ce test. */
    private class CapturingDriver : TtsDriver {
        val phrases = mutableListOf<String>()
        override val speaking: Boolean get() = false
        override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
            phrases += text
            return true
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun tempDir(): File = Files.createTempDirectory("curbvoice").toFile()

    /** Voix douce capturante : synthèse jouée, phrases dites enregistrées. */
    private fun voix(spoken: MutableList<String>) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")), safety = testSafetyVoice(),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        onSpeaking = { if (it.isNotEmpty()) spoken += it },
        synthesize = { _, _, text -> Result.success("$text-audio".toByteArray()) },
    )

    /** Voix de sécurité capturante : alertes enregistrées, rien coupé. */
    private fun securite(driver: CapturingDriver) = SafetyVoice(
        player = SpeechPlayer(File(tempDir(), "securite")),
        system = SystemTtsEngine(driver),
        settings = { Settings.Default },
        stopCurrent = {},
    )

    /** Bordure valide : label `curb`, 2,5 m, centrée, bas en bande basse (640×480). */
    private fun bordure(meters: Float = 2.5f) = DistanceReading(
        -1, "curb", meters, meters, listOf(220f, 300f, 420f, 460f),
    )

    /** Passant sans bord, hors couloir : ni bordure, ni véhicule, ni danger parlé — aucun guetteur ne parle. */
    private fun passant() = DistanceReading(
        -1, "person", 5f, 5f, listOf(20f, 300f, 120f, 460f),
    )

    /** État sonde minimal : [frames] vues, [readings] dans le calque distances. */
    private fun etat(frames: Int, readings: List<DistanceReading>) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, readings),
    )

    @Test
    fun `bordure confirmee sur 2 frames = doux dit, sans alerte`() = runTest {
        val spoken = mutableListOf<String>()
        val driver = CapturingDriver()
        val voice = voix(spoken)
        val safety = securite(driver)
        val guetteurs = ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())
        val doux = Mutex()

        obstacleFrame(etat(frames = 1, listOf(bordure())), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(frames = 2, listOf(bordure())), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        advanceUntilIdle()

        assertEquals(listOf("Bordure détectée, à midi."), spoken)
        assertTrue(driver.phrases.isEmpty())
    }

    @Test
    fun `lectures sans label bordure = silence`() = runTest {
        val spoken = mutableListOf<String>()
        val driver = CapturingDriver()
        val voice = voix(spoken)
        val safety = securite(driver)
        val guetteurs = ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())
        val doux = Mutex()

        obstacleFrame(etat(frames = 1, listOf(passant())), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        obstacleFrame(etat(frames = 2, listOf(passant())), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        advanceUntilIdle()

        assertTrue(spoken.isEmpty())
        assertTrue(driver.phrases.isEmpty())
    }

    @Test
    fun `ligne diagnostique resume lectures signal et phrase`() {
        val readings = listOf(bordure(), passant())
        val signal = CurbSignal.of(readings, 640, 480)

        val line = curbVoiceLine(readings, signal, "Bordure détectée, à midi.", crossingActive = false)

        assertTrue(line.contains("lectures=2"))
        assertTrue(line.contains("curb/2.5m/bas=460.0"))
        assertTrue(line.contains("signal=true/2.5m/FAR/flat=false"))
        assertTrue(line.contains("phrase=Bordure détectée, à midi."))
        assertTrue(line.contains("traversee=false"))
    }
}
