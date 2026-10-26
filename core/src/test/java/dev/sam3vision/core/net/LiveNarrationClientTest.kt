package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contrat HTTP de la narration du direct (`describeFrame`), vérifié sans réseau. */
class LiveNarrationClientTest {

    private fun client(server: TestHttpServer, language: SpeechLanguage = SpeechLanguage.Fr) =
        InferHubLlmClient(server.baseUrl, "cle", "modele-omni", language)

    @Test
    fun `describeFrame envoie la frame et la description precedente`() {
        val server = TestHttpServer { TestHttpServer.Response(200, NetContract.chatReply("  la personne avance vers la porte  ")) }
        try {
            val text = runBlocking {
                client(server).describeFrame("QUJD", listOf("personne", "porte"), "un vélo est posé")
            }.getOrThrow()
            assertEquals("la personne avance vers la porte", text)
            val body = JSONObject(server.requests.single().body)
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertTrue(messages.getJSONObject(0).getString("content").contains("direct"))
            val parts = messages.getJSONObject(1).getJSONArray("content")
            val prompt = parts.getJSONObject(0).getString("text")
            assertTrue(prompt.contains("personne, porte"))
            assertTrue(prompt.contains("un vélo est posé"))
            assertEquals(
                "data:image/jpeg;base64,QUJD",
                parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
            )
            assertEquals(200, body.getInt("max_tokens"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `describeFrame suit la langue de la voix`() {
        val server = TestHttpServer { TestHttpServer.Response(200, NetContract.chatReply("a person walks to the door")) }
        try {
            runBlocking {
                client(server, SpeechLanguage.En).describeFrame("QUJD", listOf("person"), "a bike")
            }
            val messages = JSONObject(server.requests.single().body).getJSONArray("messages")
            assertTrue(messages.getJSONObject(0).getString("content").contains("English"))
            val prompt = messages.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(prompt.contains("Tracked objects"))
            assertTrue(prompt.contains("Previous description"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `describeFrame borne le texte renvoye`() {
        val server = TestHttpServer { TestHttpServer.Response(200, NetContract.chatReply("a".repeat(900))) }
        try {
            val text = runBlocking { client(server).describeFrame("QUJD", emptyList(), "") }.getOrThrow()
            assertEquals(400, text.length)
        } finally {
            server.close()
        }
    }

    @Test
    fun `describeFrame sans cle echoue sans appel reseau`() {
        val server = TestHttpServer { TestHttpServer.Response(200, NetContract.chatReply("jamais lu")) }
        try {
            val noKey = InferHubLlmClient(server.baseUrl, "", "modele")
            val failure = runBlocking { noKey.describeFrame("QUJD", emptyList(), "") }.exceptionOrNull()
            assertTrue(failure is ApiError.InvalidKey)
            assertTrue(server.requests.isEmpty())
        } finally {
            server.close()
        }
    }
}