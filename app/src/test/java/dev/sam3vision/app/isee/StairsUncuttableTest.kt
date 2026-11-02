package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.VehicleStateWatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class StairsFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Escaliers incoupables (ordre user du 02/11/2026) : des escaliers en cours
 * (niveau 6) vont au bout — le niveau 0 attend au lieu de couper, la
 * Traversée attend aussi ; humains, bordures et escaliers re-détectés
 * coupent toujours, et la voiture tue en amont ne mange pas son tour.
 */
class StairsUncuttableTest {

    private val speaker = StairsFakeSpeaker()

    @Before
    fun brancher() {
        ProbeBackgroundMotion.reset()
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
        ProbeBackgroundMotion.reset()
    }

    @Test
    fun `niveau 0 attend la fin des escaliers au lieu de couper`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")

        assertEquals(listOf(StairsFakeSpeaker.Said(OutdoorLevel.STAIRS, "Escalier montant.")), speaker.said)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `verdict traversee attend la fin des escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `humain et bordure coupent toujours les escaliers`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        VoiceBus.post(OutdoorLevel.HUMAN, "Stop.")

        assertEquals(2, speaker.said.size)

        VoiceBus.reset()
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        VoiceBus.post(OutdoorLevel.CURB, "Bordure.")
        assertEquals(4, speaker.said.size)
    }

    @Test
    fun `escaliers re-detectes dernier gagnant`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.STAIRS, "4 marches.")

        assertEquals(2, speaker.said.size)
    }

    private fun rig(): ObstacleWatchers =
        ObstacleWatchers(VehicleStateWatcher(), CurbWatcher(), HazardWatcher())

    /** Lecture centrée 640×480 : boîte de [largeur] px à [meters] m. */
    private fun lecture(label: String, largeur: Float, meters: Float): DistanceReading {
        val hauteur = largeur * 0.6f
        return DistanceReading(
            -1, label, meters, meters,
            listOf(320f - largeur / 2f, 240f - hauteur / 2f, 320f + largeur / 2f, 240f + hauteur / 2f),
        )
    }

    private fun etat(frames: Int, reading: DistanceReading) = LiveProbeState(
        frames = frames, width = 640, height = 480,
        distanceLayer = DistanceLayer(640, 480, listOf(reading)),
    )

    @Test
    fun `voiture tue pendant les escaliers sans consommer son tour`() = runTest {
        VoiceBus.post(OutdoorLevel.STAIRS, "Escalier montant.")
        val guetteurs = rig()
        val t0 = System.currentTimeMillis()

        obstacleFrame(etat(1, lecture("car", 100f, 8f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(2, lecture("car", 120f, 7f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(3, lecture("car", 145f, 6f)), { SpeechLanguage.Fr }, guetteurs)

        // La 3e frame confirmerait (3 frames croissantes = mouvement parlé
        // hors escaliers) : tue par les escaliers en cours, rien de posté.
        assertEquals(1, speaker.said.size)
        // Tour d'antirépétition intact (le bus jetterait le vœu, tour mangé) :
        // la voiture reparle dès la fin des marches, côté bus.
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CAR_MOVING, t0))
    }
}
