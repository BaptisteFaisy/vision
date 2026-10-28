package dev.sam3vision.core.net

import dev.sam3vision.core.model.Analysis
import dev.sam3vision.core.model.ChatTurn
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport du client LLM (`HttpLlmClient`, API compatible OpenAI).
 *
 * Couvre ce que les tests historiques ne vérifiaient pas : méthode et en-têtes réels,
 * 400/402/500, 404 « app arrêtée », corps vide, corps illisible, JSON tronqué et délai
 * dépassé. Les cas 401, 429 et « pas de contenu » sont déjà verrouillés par
 * [LlmClientTest] et ne sont donc pas rejoués ici.
 */
class LlmContractTest {

    private fun client(url: String) = HttpLlmClient(url, NetContract.KEY, NetContract.MODEL)

    private suspend fun call(url: String): Result<Analysis> = client(url).describe("QUJD", emptyList())

    @Test
    fun `la requete est un POST JSON authentifie et identifie`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("""{"description":"ok","tags":[]}""")).use { server ->
            call(server.baseUrl).getOrThrow()

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("application/json; charset=utf-8", request.headers["content-type"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertEquals("Bearer ${NetContract.KEY}", request.headers["authorization"])
            assertFalse("le LLM ne porte pas la clé ElevenLabs", request.headers.containsKey("xi-api-key"))
            assertEquals(NetContract.MODEL, JSONObject(request.body).getString("model"))
        }
    }

    @Test
    fun `400 reste une erreur Http 400`() = runBlocking {
        NetContract.assertCode("llm", 400, ApiError.Http::class.java, ::call)
    }

    @Test
    fun `402 devient PaymentRequired`() = runBlocking {
        NetContract.assertCode("llm", 402, ApiError.PaymentRequired::class.java, ::call)
    }

    @Test
    fun `500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("llm", 500, ApiError.Http::class.java, ::call)
    }

    @Test
    fun `un 404 de Modal app arretee donne BackendStopped`() = runBlocking {
        NetContract.server(code = 404, body = "modal-http: invalid function call").use { server ->
            val error = call(server.baseUrl).exceptionOrNull()
            assertTrue("attendu BackendStopped, obtenu $error", error is ApiError.BackendStopped)
        }
    }

    @Test
    fun `un corps vide en 200 devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("llm", "", ::call)
    }

    @Test
    fun `un corps non JSON devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("llm", "<html>502 Bad Gateway</html>", ::call)
    }

    @Test
    fun `un JSON tronque devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("llm", """{"choices":[{"message":{"content":"coup""", ::call)
    }

    @Test
    fun `un delai depasse devient Timeout`() = runBlocking {
        NetContract.slowServer().use { server ->
            val result = HttpLlmClient(
                server.baseUrl,
                NetContract.KEY,
                NetContract.MODEL,
                client = NetContract.impatientClient(),
            ).describe("QUJD", emptyList())

            NetContract.assertError(result, ApiError.Timeout::class.java, "llm lent")
        }
    }

    @Test
    fun `ask porte le meme contrat de transport que describe`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("  reponse  ")).use { server ->
            val answer = client(server.baseUrl).ask("QUJD", "et la ?", emptyList()).getOrThrow()
            assertEquals("reponse", answer)

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer ${NetContract.KEY}", request.headers["authorization"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertTrue(request.headers["content-type"].orEmpty().startsWith("application/json"))
        }
    }

    @Test
    fun `askText n envoie aucune image et rend le texte du modele`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("  la porte est a droite  ")).use { server ->
            val answer = client(server.baseUrl).askText("ou est la porte ?", emptyList()).getOrThrow()
            assertEquals("la porte est a droite", answer)

            val body = server.requests.single().body
            assertFalse("aucune image dans la question orale", body.contains("image_url"))
            assertFalse(body.contains("data:image"))
            assertTrue("la question part bien au modèle", body.contains("ou est la porte ?"))
        }
    }

    @Test
    fun `un client sans reponse texte echoue explicitement au lieu de faire semblant`() = runBlocking {
        val muet = object : LlmClient {
            override suspend fun describe(jpegB64: String, labels: List<String>): Result<Analysis> =
                Result.failure(ApiError.BadResponse("jamais appelé"))

            override suspend fun ask(
                jpegB64: String,
                question: String,
                history: List<ChatTurn>,
                context: String?,
            ): Result<String> = Result.failure(ApiError.BadResponse("jamais appelé"))
        }

        val error = muet.askText("et la ?", emptyList()).exceptionOrNull()
        assertTrue("attendu NotSupported, obtenu $error", error is ApiError.NotSupported)
    }

    @Test
    fun `le raisonnement du modele est eteint par defaut`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("ok")).use { server ->
            client(server.baseUrl).askText("et la ?", emptyList()).getOrThrow()

            val sent = JSONObject(server.requests.single().body)
            assertFalse(
                "sans ce drapeau, muse-spark dépense 0,7 à 1,2 s en pensées avant de répondre",
                sent.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"),
            )
        }
    }

    @Test
    fun `le raisonnement peut etre rallume explicitement`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("ok")).use { server ->
            HttpLlmClient(server.baseUrl, NetContract.KEY, NetContract.MODEL, thinking = true)
                .askText("et la ?", emptyList()).getOrThrow()

            val sent = JSONObject(server.requests.single().body)
            assertFalse("aucun drapeau quand on garde le raisonnement", sent.has("chat_template_kwargs"))
        }
    }

    @Test
    fun `describeFrame porte le meme contrat de transport que describe`() = runBlocking {
        NetContract.server(body = NetContract.chatReply("une personne avance")).use { server ->
            client(server.baseUrl).describeFrame("QUJD", listOf("personne"), "").getOrThrow()

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer ${NetContract.KEY}", request.headers["authorization"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
        }
    }
}