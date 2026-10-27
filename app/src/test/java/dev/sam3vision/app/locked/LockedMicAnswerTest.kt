package dev.sam3vision.app.locked

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SpeechClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trajet du micro verrouillé : PCM → texte → réponse → voix. Le service Android n'est pas en
 * cause ici — seules comptent les règles (clé absente, audio trop court, échec propagé).
 */
class LockedMicAnswerTest {

    /** 500 ms de PCM 16 kHz mono : au-dessus du minimum de la dictée (400 ms). */
    private val pcm = ByteArray(16_000)

    private class Speech(private val result: Result<String>) : SpeechClient {
        var calls = 0
            private set

        override suspend fun transcribe(wav: ByteArray): Result<String> {
            calls++
            return result
        }
    }

    private class Llm(private val result: Result<String>) : LlmClient {
        var questions = mutableListOf<String>()
        var texts = 0
            private set

        override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> =
            Result.failure(ApiError.BadResponse("jamais appelé"))

        override suspend fun ask(
            jpegB64: String,
            question: String,
            history: List<ChatTurn>,
            context: String?,
        ): Result<String> = Result.failure(ApiError.BadResponse("jamais appelé avec une image"))

        override suspend fun askText(question: String, history: List<ChatTurn>, context: String?): Result<String> {
            texts++
            questions += question
            return result
        }
    }

    @Test
    fun `le trajet complet rend le texte entendu et la reponse dite`() = runBlocking {
        val speech = Speech(Result.success("  où est la porte  "))
        val llm = Llm(Result.success("la porte est à droite"))
        val dits = mutableListOf<String>()

        val done = LockedMicAnswer(speech, llm, speak = { dits += it }).run(pcm).getOrThrow()

        assertEquals("où est la porte", done.transcript)
        assertEquals("la porte est à droite", done.answer)
        assertEquals(listOf("où est la porte"), llm.questions)
        assertEquals(listOf("la porte est à droite"), dits)
        assertEquals(1, speech.calls)
    }

    @Test
    fun `sans moteur de dictee rien ne part et le message dit quoi remplir`() = runBlocking {
        val llm = Llm(Result.success("jamais"))
        val dits = mutableListOf<String>()

        val error = LockedMicAnswer(null, llm, speak = { dits += it }).run(pcm).exceptionOrNull()

        assertTrue("attendu InvalidKey, obtenu $error", error is ApiError.InvalidKey)
        assertTrue(error!!.message.orEmpty().contains("Réglages"))
        assertEquals(0, llm.texts)
        assertTrue("rien à dire sans texte", dits.isEmpty())
    }

    @Test
    fun `un audio trop court s arrete avant le reseau`() = runBlocking {
        val speech = Speech(Result.success("jamais"))
        val llm = Llm(Result.success("jamais"))

        val error = LockedMicAnswer(speech, llm, speak = {}).run(ByteArray(64)).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $error", error is ApiError.BadResponse)
        assertEquals("aucun appel réseau : le seuil est local", 0, speech.calls)
        assertEquals(0, llm.texts)
    }

    @Test
    fun `un echec de transcription remonte tel quel sans interroger le modele`() = runBlocking {
        val speech = Speech(Result.failure(ApiError.PaymentRequired("paiement requis (402)")))
        val llm = Llm(Result.success("jamais"))

        val error = LockedMicAnswer(speech, llm, speak = {}).run(pcm).exceptionOrNull()

        assertEquals("paiement requis (402)", error?.message)
        assertEquals(0, llm.texts)
    }

    @Test
    fun `un echec du modele ne fait parler personne`() = runBlocking {
        val speech = Speech(Result.success("où est la porte"))
        val llm = Llm(Result.failure(ApiError.Network("réseau")))
        val dits = mutableListOf<String>()

        val error = LockedMicAnswer(speech, llm, speak = { dits += it }).run(pcm).exceptionOrNull()

        assertTrue(error is ApiError.Network)
        assertTrue("rien à dire quand il n'y a pas de réponse", dits.isEmpty())
    }
}