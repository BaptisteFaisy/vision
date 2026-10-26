package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.Role
import dev.sam3vision.core.model.ChatTurn
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La langue de la voix descend jusqu'à la requête HTTP : c'est le système `messages`
 * qui décide de la langue de la réponse, donc de ce que la personne nous dit.
 */
class LlmRequestLanguageTest {

    private fun serverReplying(content: String) = TestHttpServer {
        TestHttpServer.Response(200, """{"choices":[{"message":{"content":"$content"}}]}""")
    }

    private fun client(server: TestHttpServer, language: SpeechLanguage) =
        InferHubLlmClient(server.baseUrl, "cle-de-test", "modele", language)

    private fun firstSystem(body: String): String = JSONObject(body).getJSONArray("messages")
        .getJSONObject(0).getString("content")

    @Test
    fun `une question en mode anglais est posée en anglais`() {
        val server = serverReplying("A chair on the left.")
        try {
            val answer = runBlocking {
                client(server, SpeechLanguage.En).ask("QUJD", "qu'y a-t-il à gauche ?", emptyList())
            }.getOrThrow()
            assertEquals("A chair on the left.", answer)
            assertTrue(firstSystem(server.requests.single().body).contains("English"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `le contexte mémoire est écrit dans la langue de la voix`() {
        val server = serverReplying("It was a bag.")
        try {
            runBlocking {
                client(server, SpeechLanguage.En).ask(
                    "QUJD",
                    "what was there?",
                    listOf(ChatTurn(Role.USER, "hier")),
                    "person on the left",
                )
            }
            val messages = JSONObject(server.requests.single().body).getJSONArray("messages")
            assertTrue(messages.getJSONObject(1).getString("content").startsWith("Context observed"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `le français reste la langue demandée en mode français`() {
        val server = serverReplying("Une chaise à gauche.")
        try {
            runBlocking { client(server, SpeechLanguage.Fr).ask("QUJD", "quoi ?", emptyList()) }
            assertTrue(firstSystem(server.requests.single().body).contains("français"))
        } finally {
            server.close()
        }
    }
}
