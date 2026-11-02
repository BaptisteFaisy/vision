package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class GpsFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Mots de guidage incoupables (ordre user du 02/11/2026) : une manœuvre
 * (niveau 11) ou un rappel (niveau 3) en cours va au bout — tout le monde
 * attend la fin du mot (≤0,5 s, clips offline) sauf la parole user (1, le
 * micro garde son silence), la bordure (5, même jour : elle interrompt
 * « droite » / « gauche », voir [CurbGuidancePriorityTest]) et les escaliers
 * (6, même jour : top priorité sur le guidage, voir
 * [StairsGuidancePriorityTest]) ; les re-posts GPS attendent aussi (pas de
 * dernier-gagnant à mi-mot : la redite 15 s couvre le tour perdu).
 */
class GpsUncuttableTest {

    private val speaker = GpsFakeSpeaker()

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
    fun `voiture et humain attendent la fin du mot`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")
        VoiceBus.post(OutdoorLevel.HUMAN, "Stop.")

        assertEquals(listOf(GpsFakeSpeaker.Said(OutdoorLevel.GPS_EVENT, "Left.")), speaker.said)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `verdict traversee attend la fin du mot`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(CrossingLevels.VERDICT, "Cross.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `rappel et manoeuvre ne se coupent pas`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        assertEquals(2, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        assertEquals(3, speaker.said.size)
    }

    @Test
    fun `repost GPS attend au lieu de couper`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `parole user coupe toujours le mot`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.USER_SPEECH, "Stop.")

        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `manoeuvre et rappel attendent, le GPS ne coupe jamais`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")

        assertEquals(listOf(GpsFakeSpeaker.Said(OutdoorLevel.HUMAN, "personne")), speaker.said)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.STAIRS, "escalier")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")

        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.STAIRS, speaker.said[1].level)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        assertEquals(3, speaker.said.size)
    }

    private fun rig(): ObstacleWatchers =
        ObstacleWatchers(CurbWatcher(), HazardWatcher())

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
    fun `frame tue pendant le mot, seul le mot est dit`() = runTest {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        val guetteurs = rig()

        obstacleFrame(etat(1, lecture("person", 100f, 3.5f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(2, lecture("person", 100f, 3.5f)), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(3, lecture("person", 100f, 3.5f)), { SpeechLanguage.Fr }, guetteurs)

        // Mot de guidage en cours : la frame est tuée, seul le mot est dit.
        assertEquals(1, speaker.said.size)
    }
}
