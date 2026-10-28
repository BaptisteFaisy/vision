package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_KEY = "cle-de-test"
private const val IMAGE_PREFIX = "data:image/jpeg;base64,"

/**
 * L'image jointe à une question. Sans photo utilisable le message `user` part **texte
 * seul** : une image vide expédiée en `data:image/jpeg;base64,` faisait répondre 400 au
 * backend, et la question restait sans réponse (bug « aucune réponse sur la photo »).
 */
class HttpLlmClientTest {

    private fun server() = TestHttpServer {
        TestHttpServer.Response(200, NetContract.chatReply("Devant toi"))
    }

    private fun client(server: TestHttpServer) = HttpLlmClient(server.baseUrl, TEST_KEY, "modele")

    private fun ask(server: TestHttpServer, image: String) {
        runBlocking { client(server).ask(image, "où est la porte ?", emptyList()) }.getOrThrow()
    }

    /** Contenu du dernier message (la question), tel qu'il part sur le réseau. */
    private fun questionContent(server: TestHttpServer): JSONArray {
        val messages = JSONObject(server.requests.single().body).getJSONArray("messages")
        return messages.getJSONObject(messages.length() - 1).getJSONArray("content")
    }

    @Test
    fun `sans image — le message user part en texte seul`() {
        val server = server()
        try {
            ask(server, "")

            val content = questionContent(server)
            assertEquals("une seule partie", 1, content.length())
            assertEquals("text", content.getJSONObject(0).getString("type"))
            assertFalse("aucune partie image", server.requests.single().body.contains("image_url"))
            assertFalse("aucun préfixe data: vide", server.requests.single().body.contains(IMAGE_PREFIX))
        } finally {
            server.close()
        }
    }

    @Test
    fun `image blanche — traitee comme absente, texte seul`() {
        val server = server()
        try {
            ask(server, "   ")

            assertEquals(1, questionContent(server).length())
            assertFalse("aucune partie image", server.requests.single().body.contains("image_url"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `avec image — le message user porte la photo en plus du texte`() {
        val server = server()
        try {
            ask(server, "QUJD")

            val content = questionContent(server)
            assertEquals("texte + image", 2, content.length())
            assertEquals("text", content.getJSONObject(0).getString("type"))
            val image = content.getJSONObject(1)
            assertEquals("image_url", image.getString("type"))
            assertEquals("${IMAGE_PREFIX}QUJD", image.getJSONObject("image_url").getString("url"))
            assertTrue("la question reste posée", content.getJSONObject(0).getString("text").isNotBlank())
        } finally {
            server.close()
        }
    }
}