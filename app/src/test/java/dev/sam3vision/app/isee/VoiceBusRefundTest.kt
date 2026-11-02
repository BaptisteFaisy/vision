package dev.sam3vision.app.isee

import dev.sam3vision.core.live.AntiRepeat
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Porte-parole faux : dits enregistrés. */
private class RefundFakeSpeaker : VoiceBusSpeaker {
    val said = mutableListOf<String>()

    override fun idle(): Boolean = true

    override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
        said += text
    }

    override fun cut() {
    }
}

/**
 * Tour rendu sur jet (SPEC v4 § 5 : une annonce avalée ne consomme pas son
 * tour) : `post` avec clé oublie la clé sur chaque chemin jeté, la garde
 * sur les chemins parlés ; sans clé, comportement inchangé (tour mangé).
 */
class VoiceBusRefundTest {

    private val speaker = RefundFakeSpeaker()

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
    fun `annonce parlee au repos = tour consomme`() {
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)
        assertEquals(listOf("Fence."), speaker.said)
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `annonce ecrasee = tour rendu`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Right.")
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)
        assertEquals(listOf("Right."), speaker.said)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `sans cle = tour mange, comportement inchange`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Right.")
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.")
        assertEquals(listOf("Right."), speaker.said)
        assertFalse(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `jet micro-user = tour rendu`() {
        VoiceBus.userSpeaking(true)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)
        assertTrue(speaker.said.isEmpty())
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `jet mot-guidage-en-cours = tour rendu`() {
        VoiceBus.post(OutdoorLevel.GPS_EVENT, "Left.")
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)
        assertEquals(listOf("Left."), speaker.said)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `jet escaliers-en-cours = tour rendu`() {
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.FENCE, "Fence.", antiRepeatKey = AntiRepeat.KEY_FENCE)
        assertEquals(listOf("5 marches."), speaker.said)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_FENCE, System.currentTimeMillis()))
    }

    @Test
    fun `cle 1 fois ecrasee = reparle a la re-detection`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Right.")
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, System.currentTimeMillis()))
        VoiceBus.post(OutdoorLevel.CROSSWALK, "Crosswalk.", antiRepeatKey = AntiRepeat.KEY_CROSSWALK_ANNOUNCE)
        assertEquals(listOf("Right."), speaker.said)
        assertTrue(AntiRepeat.allow(AntiRepeat.KEY_CROSSWALK_ANNOUNCE, System.currentTimeMillis()))
    }

    @Test
    fun `cle parametree ecrasee = seule cette cle rendue`() {
        VoiceBus.post(OutdoorLevel.HUMAN, "Right.")
        assertTrue(AntiRepeat.allow("verdict:cross", System.currentTimeMillis()))
        VoiceBus.post(100, "Cross.", antiRepeatKey = "verdict:cross")
        assertEquals(listOf("Right."), speaker.said)
        assertTrue(AntiRepeat.allow("verdict:cross", System.currentTimeMillis()))
        assertTrue(AntiRepeat.allow("verdict:dont-cross", System.currentTimeMillis()))
    }
}
