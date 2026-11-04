package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés, jamais occupé sauf consigne. */
private class CrosswalkFakeSpeaker(var idleValue: Boolean = true) : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String, val clipId: String?)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = idleValue

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text, clipId)
    }

    override fun cut() {
    }
}

/**
 * Passage piéton premier des annonces dehors (ordre user du 04/11) :
 * devant humains, rappel GPS, grillages, bordures, escaliers et poteaux —
 * sans couper la parole du user (le cran 1 tait toujours le cran 2).
 */
class CrosswalkFirstTest {

    private val speaker = CrosswalkFakeSpeaker()

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
    fun `frame : le passage bat tous les autres niveaux de la scene`() {
        val winner = decideFrame(
            listOf(
                VoiceWish(OutdoorLevel.HUMAN, "personne", AntiRepeat.KEY_HUMAN_SOFT),
                VoiceWish(OutdoorLevel.GPS_REMINDER, "rappel", AntiRepeat.KEY_GPS_REMINDER),
                VoiceWish(OutdoorLevel.FENCE, "grillage", AntiRepeat.KEY_FENCE),
                VoiceWish(OutdoorLevel.CURB, "bordure", AntiRepeat.KEY_CURB),
                VoiceWish(OutdoorLevel.STAIRS, "marches", AntiRepeat.KEY_STAIRS),
                VoiceWish(OutdoorLevel.POLE, "poteau", AntiRepeat.KEY_POLE),
                VoiceWish(OutdoorLevel.CROSSWALK, "Devant", AntiRepeat.KEY_CROSSWALK_ANNOUNCE),
            ),
        )

        assertEquals(OutdoorLevel.CROSSWALK, winner?.level)
        assertEquals("Devant", winner?.text)
    }

    @Test
    fun `bus : le passage retenu parle avant l humain retenu`() {
        speaker.idleValue = false
        VoiceBus.chatSpeaking(true)
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.post(OutdoorLevel.CROSSWALK, "Devant")

        // Rien ne coupe le chat : les deux attendent en place unique.
        assertEquals(0, speaker.said.size)

        // Fin de la réponse : le passage (plus petit niveau) parle premier.
        VoiceBus.chatSpeaking(false)
        assertEquals(
            listOf(CrosswalkFakeSpeaker.Said(OutdoorLevel.CROSSWALK, "Devant", null)),
            speaker.said,
        )
    }

    @Test
    fun `parole user : le passage se tait et garde son tour`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, System.currentTimeMillis()))

        VoiceBus.userSpeaking(true)
        assertFalse(VoiceBus.post(OutdoorLevel.CROSSWALK, "Devant", antiRepeatKey = AntiRepeat.KEY_CROSSWALK_ANNOUNCE))
        assertEquals(0, speaker.said.size)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, System.currentTimeMillis()))
        VoiceBus.userSpeaking(false)
    }
}
