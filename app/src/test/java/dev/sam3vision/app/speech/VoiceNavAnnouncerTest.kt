package dev.sam3vision.app.speech

import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNavAnnouncerTest {

    private val spoken = mutableListOf<String>()

    private fun announcer() = VoiceNavAnnouncer({ Settings.Default }) { _, text ->
        spoken += text
    }

    @Test
    fun `phrases exactes du contrat`() {
        assertEquals(
            "Go to la gare? 15 minutes. Yes?",
            VoiceNavPhrases.confirmQuestion("  la gare ", "15 minutes"),
        )
        assertEquals(
            "Go to la gare? Yes?",
            VoiceNavPhrases.confirmQuestion("la gare", null),
        )
        assertEquals("Page: la tour Eiffel.", VoiceNavPhrases.placeOpened("la tour Eiffel"))
        assertEquals("Stopped", VoiceNavPhrases.STOP_DONE)
        assertEquals("Arrived", VoiceNavPhrases.ARRIVED)
        assertEquals("GPS disabled", VoiceNavPhrases.GPS_DISABLED)
        assertEquals("GPS enabled", VoiceNavPhrases.GPS_ENABLED)
        assertEquals("Cancelled.", VoiceNavPhrases.CONFIRM_CANCELLED)
        assertEquals("Loading route.", VoiceNavPhrases.CONFIRM_LOADING)
    }

    @Test
    fun `annonces parlent la phrase attendue`() = runBlocking {
        val voice = announcer()

        voice.announceConfirmQuestion("la gare", "15 minutes")
        voice.announcePlace("la tour Eiffel")
        voice.announceStopped()
        voice.announceDisabled()
        voice.announceEnabled()
        voice.announceArrived()
        voice.announceConfirmCancelled()
        voice.announceConfirmLoading()

        assertEquals(
            listOf(
                "Go to la gare? 15 minutes. Yes?",
                "Page: la tour Eiffel.",
                "Stopped",
                "GPS disabled",
                "GPS enabled",
                "Arrived",
                "Cancelled.",
                "Loading route.",
            ),
            spoken,
        )
    }

    @Test
    fun `destination blanche refusee sans rien dire`() = runBlocking {
        val voice = announcer()

        assertTrue(voice.announceConfirmQuestion("   ", "15 minutes").isFailure)
        assertTrue(voice.announcePlace("").isFailure)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `admission sans marquage, marquage confirme la parole moteur`() {
        val announcer = VoiceNavAnnouncer()
        val turn = VoiceNavEvent.Turn(SteeringCap.Left, 100f)

        assertEquals("Straight on.", announcer.admitEvent(0L, turn))
        // Non marquée : ré-admise (moteur en échec, la réémission reparle).
        assertEquals("Straight on.", announcer.admitEvent(1_000L, turn))
        assertNull(announcer.lastPhrase)

        announcer.markSpoken(turn, 1_000L, "Straight on.")

        assertEquals("Straight on.", announcer.lastPhrase)
        assertNull(announcer.admitEvent(2_000L, turn))
    }

    @Test
    fun `voix qui jette devient echec sans lever`() = runBlocking {
        val failing = VoiceNavAnnouncer({ Settings.Default }) { _, _ ->
            throw IllegalStateException("TTS coupé")
        }

        assertTrue(failing.announceStopped().isFailure)
        assertTrue(failing.announceArrived().isFailure)
        assertTrue(failing.announceConfirmQuestion("la gare", "15 minutes").isFailure)
    }
}
