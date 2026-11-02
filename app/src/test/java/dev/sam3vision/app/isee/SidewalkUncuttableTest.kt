package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class SidewalkFakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * Trottoir absolu (ordre user du 02/11/2026, VOIX-DANGER § 45) : l'annonce
 * trottoir (niveau 10) coupe toute parole en cours — escaliers et mot de
 * guidage compris — et va au bout, personne ne la coupe. Seule la parole
 * du user passe devant. Miroir de [StairsUncuttableTest], un cran au-dessus.
 */
class SidewalkUncuttableTest {

    private val speaker = SidewalkFakeSpeaker()

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
    fun `trottoir coupe l humain et la voiture en cours`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")

        assertEquals(2, speaker.said.size)

        VoiceBus.reset()
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Car.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
        assertEquals(4, speaker.said.size)
    }

    @Test
    fun `trottoir coupe les escaliers en cours`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")

        assertEquals(
            listOf(
                SidewalkFakeSpeaker.Said(OutdoorLevel.STAIRS, "5 marches."),
                SidewalkFakeSpeaker.Said(OutdoorLevel.SIDEWALK, "Trottoir à gauche."),
            ),
            speaker.said,
        )
    }

    @Test
    fun `trottoir coupe le mot de guidage en cours`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")

        assertEquals(2, speaker.said.size)

        VoiceBus.reset()
        VoiceBus.post(OutdoorLevel.GPS_REMINDER, "Left.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à droite.")
        assertEquals(4, speaker.said.size)
    }

    @Test
    fun `trottoir coupe le chat en cours`() {
        VoiceBus.chatSpeaking(true)
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")

        assertEquals(1, speaker.said.size)
        VoiceBus.chatSpeaking(false)
    }

    @Test
    fun `tous les niveaux attendent la fin du trottoir`() {
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
        listOf(
            OutdoorLevel.CAR_MOVING, OutdoorLevel.HUMAN, OutdoorLevel.GPS_REMINDER,
            OutdoorLevel.FENCE, OutdoorLevel.CURB, OutdoorLevel.STAIRS,
            OutdoorLevel.POLE, OutdoorLevel.CROSSWALK, OutdoorLevel.CHAT,
            OutdoorLevel.GPS_EVENT, OutdoorLevel.FALLBACK, CrossingLevels.VERDICT,
        ).forEach { VoiceBus.post(it, "niveau $it.") }

        assertEquals(1, speaker.said.size)

        VoiceBus.onSpeechIdle()
        VoiceBus.post(OutdoorLevel.HUMAN, "Gauche.")
        assertEquals(2, speaker.said.size)
    }

    @Test
    fun `trottoir attend la parole du user`() {
        VoiceBus.userSpeaking(true)
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
        assertEquals(0, speaker.said.size)

        VoiceBus.userSpeaking(false)
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `trottoir re-detecte dernier gagnant`() {
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à gauche.")
        VoiceBus.post(OutdoorLevel.SIDEWALK, "Trottoir à droite.")

        assertEquals(2, speaker.said.size)
    }
}
