package dev.sam3vision.app.isee

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class CurbFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Bordure devant « droite » / « gauche » (ordre user du 02/11/2026) : la
 * bordure (niveau 5) coupe un mot de guidage en cours (manœuvre 11, rappel
 * 3) et n'est jamais coupée par eux (ni par le repli 12). Les paliers
 * 2 / 1 / 0,5 m sont couverts côté cœur ([CurbWatcher.COUNTDOWN_METERS],
 * `CurbAnnounceTest`).
 */
class CurbGuidancePriorityTest {

    private val speaker = CurbFakeSpeaker()

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
    fun `bordure coupe la manoeuvre en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.CURB, "Bordure 2 m.")

        assertEquals(
            listOf(
                CurbFakeSpeaker.Said(OutdoorLevel.GPS_EVENT, "Left."),
                CurbFakeSpeaker.Said(OutdoorLevel.CURB, "Bordure 2 m."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `bordure coupe le rappel en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        VoiceBus.post(OutdoorLevel.CURB, "Bordure 1 m.")

        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.CURB, speaker.said[1].level)
    }

    @Test
    fun `rappel attend la fin de la bordure`() {
        VoiceBus.post(OutdoorLevel.CURB, "Bordure 2 m.")
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `manoeuvre et repli n interrompent pas la bordure`() {
        VoiceBus.post(OutdoorLevel.CURB, "Bordure 0,5 m.")
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Right.")
        VoiceBus.post(OutdoorLevel.FALLBACK, "À gauche.")

        assertEquals(
            listOf(CurbFakeSpeaker.Said(OutdoorLevel.CURB, "Bordure 0,5 m.")),
            speaker.said,
        )
    }

    @Test
    fun `frame bordure parlee pendant le mot de guidage`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        val guetteurs = ObstacleWatchers(CurbWatcher(), HazardWatcher())
        val bordure = lecture("curb", 100f, 2.0f)

        // Deux frames confirment la détection : elle coupe le mot, pas l'inverse.
        obstacleFrame(etat(1, bordure), { SpeechLanguage.Fr }, guetteurs)
        obstacleFrame(etat(2, bordure), { SpeechLanguage.Fr }, guetteurs)

        assertEquals(2, speaker.said.size)
        assertEquals(OutdoorLevel.CURB, speaker.said[1].level)
        assertTrue(speaker.said[1].text.startsWith("Bordure détectée"))
    }

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
}
