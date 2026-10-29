package dev.sam3vision.app.voicenav

import dev.sam3vision.app.isee.vm.IseeMic
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La dictée de l'appui long des écrans navigation : le micro s'ouvre, la
 * transcription rend le texte, et un échec rend le micro avec son motif
 * français. Ces tests fixent les mots montrés sous le bouton : ils sont
 * écrits une seule fois, dans [VoiceNavHoldText].
 */
class VoiceNavHoldTest {

    private val mic = FakeMic()
    private val hold = VoiceNavHold(mic, { engine })

    /** Moteur de dictée simulé : présent sauf quand le test le retire. */
    private var engine: SpeechClient? = FakeClient()

    @Test
    fun `etat initial — aucune tenue, le micro n'est pas touche`() = runBlocking {
        val heard = hold.finish(Settings.Default)

        assertNull("rien à transcrire", heard)
        assertEquals("micro jamais appelé", 0, mic.stopCount)
    }

    @Test
    fun `micro indisponible — echec du demarrage, finish sans effet`() = runBlocking {
        mic.available = false

        assertFalse("démarrage refusé", hold.start())
        assertNull("rien d'armé", hold.finish(Settings.Default))
        assertEquals("micro jamais coupé", 0, mic.stopCount)
    }

    @Test
    fun `start est idempotent — une seule ouverture de micro`() {
        hold.start()
        hold.start()

        assertEquals("un seul appel micro", 1, mic.startCount)
    }

    @Test
    fun `transcription reussie — le texte revient`() = runBlocking {
        hold.start()

        val heard = hold.finish(Settings.Default)?.getOrThrow()

        assertEquals("bonjour", heard)
    }

    @Test
    fun `texte blanc — motif rien entendu, micro rendu`() = runBlocking {
        mic.text = "   "
        hold.start()

        val heard = hold.finish(Settings.Default)

        assertEquals(VoiceNavHoldText.NOTHING_HEARD, heard?.exceptionOrNull()?.message)
        assertEquals("le micro est rendu", 1, mic.abortCount)
    }

    @Test
    fun `echec ASR — le motif est rendu avec le prefixe dictee`() = runBlocking {
        mic.failure = IllegalStateException("réseau coupé")
        hold.start()

        val heard = hold.finish(Settings.Default)

        assertEquals("Dictée : réseau coupé", heard?.exceptionOrNull()?.message)
        assertEquals("le micro est rendu", 1, mic.abortCount)
    }

    @Test
    fun `echec ASR sans message — le motif affiche le texte de repli`() = runBlocking {
        mic.failure = IllegalStateException()
        hold.start()

        val heard = hold.finish(Settings.Default)

        assertEquals("Dictée : échec", heard?.exceptionOrNull()?.message)
    }

    @Test
    fun `aucun moteur de dictee — le motif est rendu et le micro jamais coupe`() = runBlocking {
        engine = null
        hold.start()

        val heard = hold.finish(Settings.Default)

        assertEquals(VoiceNavHoldText.NO_ENGINE, heard?.exceptionOrNull()?.message)
        assertEquals("le micro n'est pas coupé pour rien", 0, mic.stopCount)
        assertEquals("le micro est rendu", 1, mic.abortCount)
    }

    @Test
    fun `finish est idempotente — une seconde fois ne touche pas le micro`() = runBlocking {
        hold.start()
        hold.finish(Settings.Default)

        val second = hold.finish(Settings.Default)

        assertNull("la seconde passe ne rend rien", second)
        assertEquals("micro coupé une seule fois", 1, mic.stopCount)
        assertEquals("pas de second rejet", 0, mic.abortCount)
    }

    @Test
    fun `rejet de la tenue — la transcription suivante est refusee`() = runBlocking {
        hold.start()
        hold.discard()

        assertNull("plus rien à transcrire", hold.finish(Settings.Default))
        assertEquals("micro jamais coupé", 0, mic.stopCount)
    }

    @Test
    fun `transcription qui depasse le plafond — motif delai, micro rendu`() = runBlocking {
        mic.gate = CompletableDeferred()
        val slow = VoiceNavHold(mic, { engine }, transcribeTimeoutMs = 50)
        slow.start()

        val heard = slow.finish(Settings.Default)

        assertEquals(VoiceNavHoldText.TIMED_OUT, heard?.exceptionOrNull()?.message)
        assertEquals("le micro est rendu", 1, mic.abortCount)
    }

    @Test
    fun `session partagee — micro indisponible, motif sous le bouton`() = runBlocking {
        mic.available = false
        val ui = HoldMicUiState()

        runHoldSession(hold, Settings.Default, { true }, "indice", ui) { }

        assertEquals(VoiceNavHoldText.MIC_UNAVAILABLE, ui.error)
        assertFalse("pas d'écoute", ui.recording)
        assertFalse("pas de transcription", ui.busy)
    }

    @Test
    fun `session partagee — texte consomme, aucune erreur`() = runBlocking {
        val ui = HoldMicUiState()
        var heard: String? = null

        runHoldSession(hold, Settings.Default, { heard = it; true }, "indice", ui) { }

        assertEquals("bonjour", heard)
        assertNull("pas d'erreur", ui.error)
        assertFalse("écoute rendue", ui.recording)
        assertFalse("transcription finie", ui.busy)
    }

    @Test
    fun `session partagee — texte non consomme, indice affiche`() = runBlocking {
        val ui = HoldMicUiState()

        runHoldSession(hold, Settings.Default, { false }, "indice", ui) { }

        assertEquals("indice", ui.error)
        assertFalse("écoute rendue", ui.recording)
        assertFalse("transcription finie", ui.busy)
    }

    /** Micro simulé : compte les appels et rend le texte ou l'échec demandé. */
    private class FakeMic : IseeMic {
        var available = true
        var text = "bonjour"
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
                Result.success(text to byteArrayOf(1, 2, 3))
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
}
