package dev.sam3vision.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Micro mains libres : transitions, plafond de capture et textes exacts de la notification. */
class LockedMicTest {

    @Test
    fun `armer n ouvre pas le micro`() {
        val state = LockedMicState().armed()
        assertEquals(LockedMicPhase.READY, state.phase)
        assertEquals(0L, state.listeningSince)
        assertEquals(LockedMic.ACTION_LISTEN, state.view().action)
        assertNull("armé ne parle pas", state.view().spoken)
    }

    @Test
    fun `ouvrir le micro efface la reponse precedente`() {
        val answered = LockedMicState().armed().listen(1_000L).answered("la porte est à droite")
        val listening = answered.listen(2_000L)
        assertEquals(LockedMicPhase.LISTENING, listening.phase)
        assertEquals("", listening.answer)
        assertEquals("", listening.transcript)
        assertEquals(2_000L, listening.listeningSince)
    }

    @Test
    fun `le plafond envoie tout seul et pas avant`() {
        val state = LockedMicState().armed().listen(10_000L)
        assertFalse(state.shouldSend(10_000L + LockedMic.MAX_RECORD_MS - 1))
        assertTrue(state.shouldSend(10_000L + LockedMic.MAX_RECORD_MS))
        assertTrue(state.shouldSend(10_000L + LockedMic.MAX_RECORD_MS + 5_000L))
    }

    @Test
    fun `sans capture en cours rien ne part tout seul`() {
        assertFalse(LockedMicState().shouldSend(Long.MAX_VALUE))
        assertFalse(LockedMicState().armed().shouldSend(Long.MAX_VALUE))
        assertFalse(LockedMicState().armed().transcribing().shouldSend(Long.MAX_VALUE))
    }

    @Test
    fun `la duree capturee se lit en millisecondes`() {
        val state = LockedMicState().armed().listen(1_000L)
        assertEquals(0L, state.elapsedMs(1_000L))
        assertEquals(2_500L, state.elapsedMs(3_500L))
        assertEquals("hors capture, zéro", 0L, state.transcribing().elapsedMs(9_999L))
    }

    @Test
    fun `la phase qui ecoute annonce et propose d envoyer`() {
        val view = LockedMicState().armed().listen(0L).view()
        assertEquals(LockedMic.TITLE_LISTENING, view.title)
        assertEquals(LockedMic.ACTION_SEND, view.action)
        assertEquals("J'écoute", view.spoken)
        assertTrue(view.message.contains(LockedMic.ACTION_SEND))
    }

    @Test
    fun `la transcription affiche le texte entendu et ne parle pas`() {
        val transcribing = LockedMicState().armed().listen(0L).copy(transcript = "où est la porte").transcribing()
        val view = transcribing.view()
        assertEquals("où est la porte", view.message)
        assertNull(view.action)
        assertNull(view.spoken)
    }

    @Test
    fun `la reponse est affichee et prononcee telle quelle`() {
        val view = LockedMicState().armed().listen(0L).answered("la porte est à droite").view()
        assertEquals(LockedMic.TITLE_ANSWER, view.title)
        assertEquals("la porte est à droite", view.message)
        assertEquals("la porte est à droite", view.spoken)
        assertEquals(LockedMic.ACTION_LISTEN, view.action)
    }

    @Test
    fun `un echec garde le message exact et le prononce`() {
        val failed = LockedMicState().armed().failed("clé API absente — renseigne-la dans Réglages")
        val view = failed.view()
        assertEquals(LockedMicPhase.FAILED, failed.phase)
        assertEquals("clé API absente — renseigne-la dans Réglages", view.message)
        assertEquals("Échec. clé API absente — renseigne-la dans Réglages", view.spoken)
        assertEquals(LockedMic.ACTION_LISTEN, view.action)
    }

    @Test
    fun `un message trop long est coupe proprement`() {
        val long = "a".repeat(LockedMic.MAX_MESSAGE_CHARS + 40)
        val message = LockedMicState().armed().answered(long).view().message
        assertEquals(LockedMic.MAX_MESSAGE_CHARS, message.length)
        assertTrue(message.endsWith("…"))
    }

    @Test
    fun `l arret remet tout a zero`() {
        val off = LockedMicState().armed().listen(5L).answered("réponse").off()
        assertEquals(LockedMicPhase.OFF, off.phase)
        assertEquals("", off.answer)
        assertNull(off.view().action)
    }
}