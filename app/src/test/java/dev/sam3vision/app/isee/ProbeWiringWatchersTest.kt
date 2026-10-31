package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parole des guetteurs : la bordure (imminente comme pré-alerte) passe même
 * en Traversée pour l'imminente et même après un stop — une alerte de
 * sécurité ne se tait jamais pour une autre phrase ; le bateau reste doux
 * et coupé en Traversée — et les détections sont reconstruites des lectures.
 * L'ordre P1-P5 vit dans `ObstacleArbiterTest` ; la porte frames fige la
 * confirmation véhicules sur réémission sans nouvelle frame SAM.
 */
class ProbeWiringWatchersTest {

    private fun cue(tier: CurbTier) = CurbCue("marche", 0.5f, tier)

    /** Pilote TTS système muet : la sécurité ne parle pas dans ce test (miroir ProbeStairsGateTest). */
    private class SilentDriver : TtsDriver {
        override val speaking: Boolean get() = false
        override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float): Boolean = true
        override fun stop() = Unit
        override fun shutdown() = Unit
    }

    private fun tempDir(): File = Files.createTempDirectory("watchers").toFile()

    /** Voiture mesurée à [meters] m, boîte centrée de [largeur] px. */
    private fun voiture(largeur: Float, meters: Float): DistanceReading {
        val hauteur = largeur * 0.6f
        return DistanceReading(-1, "car", meters, meters,
            listOf(320f - largeur / 2f, 240f - hauteur / 2f, 320f + largeur / 2f, 240f + hauteur / 2f))
    }

    /** État sonde minimal : [frames] vues, la voiture dans le calque distances. */
    private fun etat(frames: Int, largeur: Float, meters: Float) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, listOf(voiture(largeur, meters))),
    )

    @Test
    fun `marche imminente passe meme en traversee`() {
        assertTrue(shouldSpeakCurb(cue(CurbTier.NEAR), crossingActive = true))
        assertTrue(shouldSpeakCurb(cue(CurbTier.NEAR), crossingActive = false))
    }

    @Test
    fun `pre-alerte et bateau doux, coupes en traversee`() {
        assertFalse(shouldSpeakCurb(cue(CurbTier.FAR), crossingActive = true))
        assertFalse(shouldSpeakCurb(cue(CurbTier.NONE), crossingActive = true))
        assertTrue(shouldSpeakCurb(cue(CurbTier.FAR), crossingActive = false))
        assertTrue(shouldSpeakCurb(cue(CurbTier.NONE), crossingActive = false))
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

    @Test
    fun `reemission meme compteur, confirmation vehicule fige`() = runTest {
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default },
        )
        val safety = SafetyVoice(
            player = SpeechPlayer(File(tempDir(), "securite")),
            system = SystemTtsEngine(SilentDriver()),
            settings = { Settings.Default },
            stopCurrent = {},
        )
        val guetteurs = ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())
        val doux = Mutex()

        obstacleFrame(etat(frames = 1, largeur = 100f, meters = 5f), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)
        assertFalse(guetteurs.vehicle.movingThisFrame)

        // Même compteur, boîte qui grandit et se rapproche : sans la porte,
        // `update` prouverait un mouvement (movingThisFrame à vrai).
        obstacleFrame(etat(frames = 1, largeur = 110f, meters = 4.5f), this, voice, { true }, { SpeechLanguage.Fr }, safety, guetteurs, doux)

        assertFalse("compteur rejoué : update non relancé", guetteurs.vehicle.movingThisFrame)
        assertEquals(1, guetteurs.lastVehicleFrames)
    }
}
