package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.model.Role
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_KEY = "cle-de-test"

private fun serverReplying(content: String) = TestHttpServer {
    TestHttpServer.Response(200, NetContract.chatReply(content))
}

class LlmClientTest {

    private fun client(server: TestHttpServer) =
        InferHubLlmClient(server.baseUrl, TEST_KEY, "ali/qwen3.8-omni-flash")

    @Test
    fun `describe accepte une reponse JSON`() {
        val server = serverReplying("""{"description":"un chaton dort","tags":["chat","canapé"]}""")
        try {
            val analysis = runBlocking { client(server).describe("QUJD", listOf("chat")) }.getOrThrow()
            assertEquals("un chaton dort", analysis.description)
            assertEquals(listOf("chat", "canapé"), analysis.tags)
            assertTrue(analysis.objects.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `describe tolere du texte libre`() {
        val server = serverReplying("Voici une photo : il y a une table en bois.")
        try {
            val analysis = runBlocking { client(server).describe("QUJD", listOf("table", "chaise")) }.getOrThrow()
            assertEquals("Voici une photo : il y a une table en bois.", analysis.description)
            assertEquals(listOf("table", "chaise"), analysis.tags)
        } finally {
            server.close()
        }
    }

    @Test
    fun `describe extrait le JSON noye dans du texte`() {
        val server = serverReplying("Voilà ! ```json\n{\"description\":\"plage\",\"tags\":[\"mer\"]}\n``")
        try {
            val analysis = runBlocking { client(server).describe("QUJD", emptyList()) }.getOrThrow()
            assertEquals("plage", analysis.description)
            assertEquals(listOf("mer"), analysis.tags)
        } finally {
            server.close()
        }
    }

    @Test
    fun `la requete porte Bearer et le modele`() {
        val server = serverReplying("""{"description":"ok","tags":[]}""")
        try {
            runBlocking { client(server).describe("QUJD", emptyList()) }
            val request = server.requests.single()
            assertEquals("/chat/completions", request.path)
            assertEquals("Bearer $TEST_KEY", request.headers["authorization"])
            val body = JSONObject(request.body)
            assertEquals("ali/qwen3.8-omni-flash", body.getString("model"))
            assertEquals(0.2, body.getDouble("temperature"), 0.001)
            val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
            assertEquals("data:image/jpeg;base64,QUJD", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `ask envoie au plus 20 tours d historique`() {
        val server = serverReplying("réponse")
        val history = (0 until 30).map { ChatTurn(Role.USER, "tour-%02d".format(it)) }
        try {
            val answer = runBlocking { client(server).ask("QUJD", "qu est-ce ?", history) }.getOrThrow()
            assertEquals("réponse", answer)
            val messages = JSONObject(server.requests.single().body).getJSONArray("messages")
            // system + 20 tours + question courante
            assertEquals(22, messages.length())
            val flat = messages.toString()
            assertTrue("les vieux tours doivent sauter", !flat.contains("tour-09"))
            assertTrue("les derniers tours doivent rester", flat.contains("tour-29"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `401 donne cle invalide`() {
        val server = TestHttpServer { TestHttpServer.Response(401, """{"error":"bad key"}""") }
        try {
            val failure = runBlocking { client(server).describe("QUJD", emptyList()) }.exceptionOrNull()
            assertTrue("attendu InvalidKey, obtenu $failure", failure is ApiError.InvalidKey)
            assertTrue(failure?.message?.contains("clé invalide") == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun `429 donne limite atteinte`() {
        val server = TestHttpServer { TestHttpServer.Response(429, "{}") }
        try {
            val failure = runBlocking { client(server).describe("QUJD", emptyList()) }.exceptionOrNull()
            assertTrue(failure is ApiError.RateLimited)
            assertTrue(failure?.message?.contains("limite atteinte") == true)
        } finally {
            server.close()
        }
    }

    @Test
    fun `cle absente echoue sans appel reseau`() {
        val server = serverReplying("devrait etre inutilise")
        try {
            val noKey = InferHubLlmClient(server.baseUrl, "", "modele")
            val failure = runBlocking { noKey.describe("QUJD", emptyList()) }.exceptionOrNull()
            assertTrue(failure is ApiError.InvalidKey)
            assertTrue(server.requests.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `reponse sans contenu donne BadResponse`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"choices":[]}""") }
        try {
            val failure = runBlocking { client(server).describe("QUJD", emptyList()) }.exceptionOrNull()
            assertTrue(failure is ApiError.BadResponse)
        } finally {
            server.close()
        }
    }
}
