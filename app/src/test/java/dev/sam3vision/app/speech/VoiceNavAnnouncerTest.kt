package dev.sam3vision.app.speech

import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceNavAnnouncerTest {

    private val spoken = mutableListOf<String>()

    private fun announcer() = VoiceNavAnnouncer({ Settings.Default }) { _, text ->
        spoken += text
    }

    @Test
    fun `phrases exactes du contrat`() {
        assertEquals("Destination : la gare", VoiceNavPhrases.destinationHeard("  la gare "))
        assertEquals("Voici la fiche de la tour Eiffel", VoiceNavPhrases.placeOpened("la tour Eiffel"))
        assertEquals("Guidage arrêté", VoiceNavPhrases.STOP_DONE)
        assertEquals("Arrivée à destination", VoiceNavPhrases.ARRIVED)
    }

    @Test
    fun `annonces parlent la phrase attendue`() = runBlocking {
        val voice = announcer()

        voice.announceHeard("la gare")
        voice.announcePlace("la tour Eiffel")
        voice.announceStopped()
        voice.announceArrived()

        assertEquals(
            listOf(
                "Destination : la gare",
                "Voici la fiche de la tour Eiffel",
                "Guidage arrêté",
                "Arrivée à destination",
            ),
            spoken,
        )
    }

    @Test
    fun `destination blanche refusee sans rien dire`() = runBlocking {
        val voice = announcer()

        assertTrue(voice.announceHeard("   ").isFailure)
        assertTrue(voice.announcePlace("").isFailure)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `voix qui jette devient echec sans lever`() = runBlocking {
        val failing = VoiceNavAnnouncer({ Settings.Default }) { _, _ ->
            throw IllegalStateException("TTS coupé")
        }

        assertTrue(failing.announceStopped().isFailure)
        assertTrue(failing.announceArrived().isFailure)
        assertTrue(failing.announceHeard("la gare").isFailure)
    }
}
