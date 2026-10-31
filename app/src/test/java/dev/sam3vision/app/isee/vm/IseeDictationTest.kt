package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.ChatEntry
import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La dictée de l'appui long : le micro s'ouvre, la transcription rend texte + PCM, et
 * un échec rend le micro sans casser la conversation déjà affichée. Ces tests fixent
 * les mots montrés à l'écran : ils sont écrits une seule fois, dans [IseeStatusText].
 */
class IseeDictationTest {

    private val state = MutableStateFlow(IseeUiState())
    private val mic = FakeMic()
    private val dictation = IseeDictation(mic, { engine }, { fn -> state.value = fn(state.value) })

    /** Moteur de dictée simulé : présent sauf quand le test le retire. */
    private var engine: SpeechClient? = FakeClient()

    @Test
    fun `etat initial — aucune tenue, le micro n'est pas touche`() = runBlocking {
        val heard = dictation.finish(Settings.Default)

        assertNull("rien à transcrire", heard)
        assertEquals("micro jamais appelé", 0, mic.stopCount)
        assertFalse("pas de transcription en cours", state.value.transcribing)
        assertNull("aucun motif affiché", state.value.status)
    }

    @Test
    fun `micro indisponible — le motif est affiche et la conversation reste la`() {
        mic.available = false

        dictation.start()

        assertEquals(IseeStatusText.MIC_UNAVAILABLE, state.value.status)
        assertTrue("les bulles ne bougent pas", state.value.entries.isEmpty())
    }

    @Test
    fun `start est idempotent — une seule ouverture de micro`() {
        dictation.start()
        dictation.start()

        assertEquals("un seul appel micro", 1, mic.startCount)
    }

    @Test
    fun `transcription reussie — le texte et le pcm reviennent, transcribing remis a zero`() = runBlocking {
        dictation.start()

        val heard = dictation.finish(Settings.Default)

        assertEquals("bonjour", heard?.text)
        assertEquals(3, heard?.audio?.size)
        assertFalse("plus de transcription en cours", state.value.transcribing)
    }

    @Test
    fun `echec ASR — le motif est affiche, le micro rendu, la conversation intacte`() = runBlocking {
        state.value = state.value.copy(entries = listOf(bubble()), status = null)
        mic.failure = IllegalStateException("réseau coupé")
        dictation.start()

        val heard = dictation.finish(Settings.Default)

        assertNull("rien de transcrit", heard)
        assertEquals("Dictation: réseau coupé", state.value.status)
        assertEquals("la bulle reste à l'écran", 1, state.value.entries.size)
        assertEquals("le micro est rendu", 1, mic.abortCount)
        assertFalse("plus de transcription en cours", state.value.transcribing)
    }

    @Test
    fun `echec ASR sans message — le motif affiche le texte de repli`() = runBlocking {
        mic.failure = IllegalStateException()
        dictation.start()

        dictation.finish(Settings.Default)

        assertEquals("Dictation: failed", state.value.status)
    }

    @Test
    fun `aucun moteur de dictee — le motif est dit et le micro rendu`() = runBlocking {
        engine = null
        dictation.start()

        val heard = dictation.finish(Settings.Default)

        assertNull("rien de transcrit", heard)
        assertEquals(
            "Dictation: No dictation engine configured (Settings)",
            state.value.status,
        )
        assertEquals("le micro n'est pas coupé pour rien", 0, mic.stopCount)
        assertEquals("le micro est rendu", 1, mic.abortCount)
    }

    @Test
    fun `finish est idempotente — une seconde fois ne touche pas le micro`() = runBlocking {
        dictation.start()
        dictation.finish(Settings.Default)

        val second = dictation.finish(Settings.Default)

        assertNull("la seconde passe ne rend rien", second)
        assertEquals("micro coupé une seule fois", 1, mic.stopCount)
        assertEquals("pas de second rejet", 0, mic.abortCount)
    }

    @Test
    fun `rejet de la tenue — la transcription suivante est refusee`() = runBlocking {
        dictation.start()
        dictation.discard()

        assertNull("plus rien à transcrire", dictation.finish(Settings.Default))
        assertEquals("micro jamais coupé", 0, mic.stopCount)
    }

    @Test
    fun `pendant la transcription — l'etape est affichee`() = runBlocking {
        mic.gate = CompletableDeferred()
        dictation.start()
        val job = launch { dictation.finish(Settings.Default) }
        withTimeout(2_000) { while (!state.value.transcribing) delay(5) }

        assertEquals(IseeStatusText.STEP_TRANSCRIBING, state.value.status)

        mic.gate?.complete(Unit)
        job.join()
    }

    @Test
    fun `transcription reussie — l'etape s'efface`() = runBlocking {
        dictation.start()

        dictation.finish(Settings.Default)

        assertNull("aucune étape résiduelle", state.value.status)
    }

    @Test
    fun `micro rendu — le rappel de fin part en succes comme en echec`() = runBlocking {
        var settled = 0
        val hooked = IseeDictation(mic, { engine }, { fn -> state.value = fn(state.value) }, onSettled = { settled++ })
        hooked.start()
        hooked.finish(Settings.Default)
        mic.failure = IllegalStateException("panne")
        hooked.start()
        hooked.finish(Settings.Default)

        assertEquals("succès + échec", 2, settled)
    }

    @Test
    fun `transcription qui depasse le plafond — motif d'etape, micro rendu`() = runBlocking {
        mic.gate = CompletableDeferred()
        val slow = IseeDictation(mic, { engine }, { fn -> state.value = fn(state.value) }, transcribeTimeoutMs = 50)
        slow.start()

        val heard = slow.finish(Settings.Default)

        assertNull("rien de transcrit", heard)
        assertEquals("Transcribing timed out — try again", state.value.status)
        assertEquals("le micro est rendu", 1, mic.abortCount)
        assertFalse("plus de transcription en cours", state.value.transcribing)
    }

    /** Micro simulé : compte les appels et rend le texte ou l'échec demandé. */
    private class FakeMic : IseeMic {
        var available = true
        var failure: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var startCount = 0
        var stopCount = 0
        var abortCount = 0

        override fun start(): Boolean {
            startCount++
            return available
        }

        override suspend fun stop(client: SpeechClient): Result<Pair<String, ByteArray>> {
            stopCount++
            gate?.await()
            val error = failure
            return if (error != null) {
                Result.failure(error)
            } else {
                Result.success("bonjour" to byteArrayOf(1, 2, 3))
            }
        }

        override fun abort() {
            abortCount++
        }
    }

    /** Client de transcription simulé : la capture est simulée, il n'est jamais appelé. */
    private class FakeClient : SpeechClient {
        override suspend fun transcribe(wav: ByteArray): Result<String> = Result.success("bonjour")
    }

    /** Une bulle déjà à l'écran, pour vérifier qu'elle survit à un échec. */
    private fun bubble() = ChatEntry.UserBubble(1L, "déjà là", null)
}