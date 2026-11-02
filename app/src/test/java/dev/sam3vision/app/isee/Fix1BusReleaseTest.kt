package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingLevels
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés (même couture que EchelleOverwriteTest). */
private class Fix1FakeSpeaker : VoiceBusSpeaker {
    data class Said(val level: Int, val text: String)

    val said = mutableListOf<Said>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += Said(level, text)
    }

    override fun cut() = Unit
}

/**
 * FIX 1 : chaque coupe hors-bus (parole du user, fin de guidage, intro
 * coupée par la voix) rend le bus vocal ([VoiceBus.onSpeechIdle]) ; la
 * fermeture complète l'oublie ([VoiceBus.reset]). Sans ça, un niveau tenu
 * (ex. 12) muterait à jamais tout niveau plus bas (ex. verdict 100).
 */
class Fix1BusReleaseTest {

    private val speaker = Fix1FakeSpeaker()

    @Before
    fun brancher() {
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
    }

    @Test
    fun `coupe rend le bus, verdict accepte apres un niveau 12`() {
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        VoiceBus.post(CrossingLevels.VERDICT, "verdict")
        assertEquals(1, speaker.said.size)

        // Ce que fait désormais chaque coupe hors-bus après player.stop().
        VoiceBus.onSpeechIdle()

        VoiceBus.post(CrossingLevels.VERDICT, "verdict")
        assertEquals(2, speaker.said.size)
        assertEquals(CrossingLevels.VERDICT, speaker.said.last().level)
    }

    @Test
    fun `coupe no-op quand le user tient le micro`() {
        VoiceBus.userSpeaking(true)
        VoiceBus.post(OutdoorLevel.CAR_MOVING, "Voiture.")
        VoiceBus.onSpeechIdle()
        VoiceBus.userSpeaking(false)

        // Le niveau 0 a gardé la main : un niveau plus bas reste tué.
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(
            listOf(Fix1FakeSpeaker.Said(OutdoorLevel.CAR_MOVING, "Voiture.")),
            speaker.said,
        )
    }

    @Test
    fun `coupe no-op quand le chat lit`() {
        VoiceBus.chatSpeaking(true)
        VoiceBus.post(OutdoorLevel.HUMAN, "personne")
        VoiceBus.onSpeechIdle()
        VoiceBus.chatSpeaking(false)

        // La personne parlait encore : le repli reste tué.
        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(1, speaker.said.size)
    }

    @Test
    fun `fermeture oublie tout meme avec des prises coincees`() {
        VoiceBus.userSpeaking(true)
        VoiceBus.chatSpeaking(true)
        // Ce que fait désormais onClosed après player.stop().
        VoiceBus.reset()
        VoiceBus.userSpeaking(false)
        VoiceBus.chatSpeaking(false)

        VoiceBus.post(OutdoorLevel.FALLBACK, "repli")
        assertEquals(1, speaker.said.size)
    }
}
