package dev.sam3vision.app.tap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Machine à états du tap court : chaque transition, plus la liste blanche des mots. */
class TapMachineTest {

    @Test
    fun `un tap au repos demande le demarrage`() {
        assertEquals(TapCommand.START, tapCommand(TapSnapshot(TapPhase.IDLE, 0L)))
    }

    @Test
    fun `un tap pendant l enregistrement demande arret et envoi`() {
        assertEquals(TapCommand.STOP_AND_SEND, tapCommand(TapSnapshot(TapPhase.RECORDING, 3_000L)))
    }

    @Test
    fun `un tap trop tot est rememore puis execute des la duree exploitable`() {
        assertEquals(TapCommand.NONE, tapCommand(TapSnapshot(TapPhase.RECORDING, 100L), minRecordingMs = 250L))
        // le battement honore la demande dès la durée minimale
        assertEquals(TapCommand.STOP_AND_SEND, tickCommand(TapSnapshot(TapPhase.RECORDING, 350L), pendingStop = true))
        assertEquals(TapCommand.NONE, tickCommand(TapSnapshot(TapPhase.RECORDING, 200L), pendingStop = true))
        // un nouveau tap trop tôt reste remisé : rien avant la durée exploitable
        assertEquals(TapCommand.NONE, tapCommand(TapSnapshot(TapPhase.RECORDING, 120L)))
        assertEquals(TapCommand.STOP_AND_SEND, tapCommand(TapSnapshot(TapPhase.RECORDING, 350L)))
    }

    @Test
    fun `un tap pendant l envoi est ignore sans annonce`() {
        assertEquals(TapCommand.IGNORE, tapCommand(TapSnapshot(TapPhase.SENDING, 0L)))
    }

    @Test
    fun `le battement arrete l enregistrement a la duree maximale`() {
        assertEquals(TapCommand.STOP_AND_SEND, tickCommand(TapSnapshot(TapPhase.RECORDING, 60_000L), maxRecordingMs = 60_000L))
        assertEquals(TapCommand.NONE, tickCommand(TapSnapshot(TapPhase.RECORDING, 59_900L), maxRecordingMs = 60_000L))
        assertEquals(TapCommand.NONE, tickCommand(TapSnapshot(TapPhase.IDLE, 99_999L), maxRecordingMs = 60_000L))
    }

    @Test
    fun `les trois et seuls mots annonces sont Launched Sent Failed`() {
        assertEquals(listOf("Launched", "Sent", "Failed"), TapConfig.words.map { it.spoken })
        assertTrue(announcementAllowed("Launched"))
        assertTrue(announcementAllowed(" Sent "))
        assertTrue(announcementAllowed("Failed"))
        assertFalse(announcementAllowed("Voici la description de la vidéo."))
        assertFalse(announcementAllowed("Launched, puis analyse"))
    }

    @Test
    fun `les clips portent un nom de fichier stable`() {
        assertEquals("launched.mp3", Announcement.LAUNCHED.clipFile)
        assertEquals("sent.mp3", Announcement.SENT.clipFile)
        assertEquals("failed.mp3", Announcement.FAILED.clipFile)
    }

    @Test
    fun `la periodicite du nettoyage est d une heure au minimum`() {
        assertEquals(60L, dev.sam3vision.app.cleanup.CleanupConfig.intervalMinutes(1))
        assertEquals(60L, dev.sam3vision.app.cleanup.CleanupConfig.intervalMinutes(-3))
    }
}
