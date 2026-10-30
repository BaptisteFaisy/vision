package dev.sam3vision.app.glasses

import dev.sam3vision.app.PcmSource
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Question mains-libres ([GlassesTalk]) : micro des lunettes, photo-contexte
 * et transcription, avec des doublures — aucun SDK, aucun Android.
 * `Dispatchers.Unconfined` exécute les coroutines aussitôt : les envois sont
 * observables sans attendre, et le délai auto est neutralisé ([LONG_LISTEN_MS]).
 */
class GlassesTalkTest {

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val cues = mutableListOf<GlassesTalkCue>()
    private val listeningStates = mutableListOf<Boolean>()
    private val heard = mutableListOf<Triple<File?, String, ByteArray>>()
    private val notes = mutableListOf<GlassesMessage>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `le premier appui long ouvre le micro et l ecoute`() {
        val talk = talk(mic = fakeMic())

        assertEquals(TalkPress.STARTED, talk.press())
        assertTrue(talk.listening)
        assertTrue(talk.busy)
        assertEquals(listOf(GlassesTalkCue.LISTENING), cues)
        assertEquals(listOf(true), listeningStates)
    }

    @Test
    fun `le second appui long envoie la question transcrite`() {
        val talk = talk(mic = fakeMic(), transcript = "où sont les toilettes")
        talk.press()

        assertEquals(TalkPress.SENDING, talk.press())
        assertFalse(talk.listening)
        assertFalse(talk.busy)
        assertEquals(1, heard.size)
        assertEquals("où sont les toilettes", heard.single().second)
        assertTrue(heard.single().third.isNotEmpty())
        assertEquals(listOf(GlassesTalkCue.LISTENING, GlassesTalkCue.SENT), cues)
        assertEquals(listOf(true, false), listeningStates)
    }

    @Test
    fun `sans micro ni session, le repli est annonce`() {
        val talk = talk(mic = null)

        assertEquals(TalkPress.MIC_UNAVAILABLE, talk.press())
        assertFalse(talk.listening)
        assertTrue(heard.isEmpty())
    }

    @Test
    fun `micro refuse, le repli est annonce et dit`() {
        val talk = talk(mic = fakeMic(starts = false))

        assertEquals(TalkPress.MIC_UNAVAILABLE, talk.press())
        assertFalse(talk.listening)
        assertEquals(1, notes.size)
    }

    @Test
    fun `fin du delai en ecoute, la question part`() {
        val talk = talk(mic = fakeMic(), transcript = "quelle heure est-il")
        talk.press()
        talk.timeout()

        assertEquals(1, heard.size)
        assertEquals("quelle heure est-il", heard.single().second)
        assertFalse(talk.listening)
    }

    @Test
    fun `fin du delai hors ecoute, rien ne part`() {
        val talk = talk(mic = fakeMic())
        talk.timeout()

        assertTrue(heard.isEmpty())
        assertTrue(listeningStates.isEmpty())
    }

    @Test
    fun `l abandon rend le micro et ferme sans envoyer`() {
        val mic = fakeMic()
        val talk = talk(mic = mic)
        talk.press()
        talk.cancel()

        assertTrue(mic.aborted)
        assertFalse(talk.listening)
        assertFalse(talk.busy)
        assertTrue(heard.isEmpty())
        assertEquals(listOf(true, false), listeningStates)
    }

    @Test
    fun `l injection debug part sans micro`() {
        val talk = talk(mic = null)
        talk.injectTranscript("décris la rue")

        assertEquals(1, heard.size)
        assertEquals("décris la rue", heard.single().second)
        assertFalse(talk.listening)
    }

    @Test
    fun `l injection vide est ignoree`() {
        val talk = talk(mic = fakeMic())
        talk.injectTranscript("  ")

        assertTrue(heard.isEmpty())
    }

    @Test
    fun `la photo-contexte accompagne la question`() {
        val photo = File.createTempFile("lunettes", ".jpg")
        try {
            val talk = talk(mic = fakeMic(), photo = photo)
            talk.press()

            assertEquals(TalkPress.SENDING, talk.press())
            assertEquals(photo, heard.single().first)
        } finally {
            photo.delete()
        }
    }

    @Test
    fun `sans photo, la question part en texte seul`() {
        val talk = talk(mic = fakeMic(), photo = null)
        talk.press()

        assertEquals(TalkPress.SENDING, talk.press())
        assertNull(heard.single().first)
    }

    private fun talk(
        mic: FakeMic?,
        photo: File? = null,
        transcript: String = "bonjour",
    ) = GlassesTalk(
        scope = scope,
        audio = { mic },
        snapPhoto = { photo },
        engine = { FakeSpeech(transcript) },
        settings = { Settings("", "", "", "") },
        beep = cues::add,
        onListening = listeningStates::add,
        onHeard = { heardPhoto, text, pcm -> heard.add(Triple(heardPhoto, text, pcm)) },
        onNote = notes::add,
        listenMs = LONG_LISTEN_MS,
    )

    private fun fakeMic(starts: Boolean = true) = FakeMic(starts)

    private class FakeMic(private val starts: Boolean) : PcmSource {
        var aborted = false

        override fun start(): Boolean = starts

        override fun stopAndCollect(): ByteArray = ByteArray(PCM_BYTES)

        override fun abort() {
            aborted = true
        }
    }

    private class FakeSpeech(private val transcript: String) : SpeechClient {
        override suspend fun transcribe(wav: ByteArray): Result<String> = Result.success(transcript)
    }

    private companion object {
        /** Délai neutralisé : l'envoi auto ne part jamais pendant les tests. */
        const val LONG_LISTEN_MS = 60_000L

        /** 1 s de PCM 16 kHz mono : au-dessus du minimum de 400 ms de la dictée. */
        const val PCM_BYTES = 32_000
    }
}
