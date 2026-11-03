package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class OfferFakeSpeaker : VoiceBusSpeaker {
    val said = mutableListOf<String>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += text
    }

    override fun cut() {
    }
}

/**
 * Le verdict du bus fait foi : `offer` renvoie `false` quand le bus jette
 * (pas de porte-parole, écrasé), pour que le tick ne marque pas servie
 * une phrase jamais dite ; `true` quand elle passe.
 */
class ProbePriorityTest {

    private val speaker = OfferFakeSpeaker()

    @Before
    fun brancher() {
        AntiRepeat.reset()
        VoiceBus.reset()
        VoiceBus.speaker = speaker
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        AntiRepeat.reset()
    }

    @Test
    fun `sans porte-parole offre refusee`() {
        VoiceBus.speaker = null

        assertFalse(offer(VoiceWish(OutdoorLevel.HUMAN, "Person.", "probe-test-humain"), 1_000L))
        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `bus libre offre postee`() {
        assertTrue(offer(VoiceWish(OutdoorLevel.HUMAN, "Person.", "probe-test-humain"), 1_000L))
        assertEquals(listOf("Person."), speaker.said)
    }

    @Test
    fun `voeu vide refuse sans toucher au bus`() {
        assertFalse(offer(null, 1_000L))
        assertFalse(offer(VoiceWish(OutdoorLevel.HUMAN, "  ", "probe-test-vide"), 1_000L))
        assertTrue(speaker.said.isEmpty())
    }

    @Test
    fun `voeu ecrase refuse et rejouable`() {
        assertTrue(offer(VoiceWish(OutdoorLevel.HUMAN, "Person.", "probe-test-fort"), 1_000L))
        assertFalse(offer(VoiceWish(OutdoorLevel.GPS_REMINDER, "Turn.", "probe-test-faible"), 1_100L))
        VoiceBus.reset()
        assertTrue(offer(VoiceWish(OutdoorLevel.GPS_REMINDER, "Turn.", "probe-test-faible"), 1_200L))
    }
}
