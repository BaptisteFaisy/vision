package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEST_KEY = "cle-eleven-test"

/** WAV minimal lisible en ASCII : en-tête + échantillons factices. */
private val WAV = ("RIFF" + "x".repeat(36) + "donnees-a-transcrire").toByteArray()

private fun sttServer(code: Int = 200, body: String = """{"language_code":"fra","text":"Bonjour, ceci est un test."}""") =
    TestHttpServer { TestHttpServer.Response(code, body) }

class ElevenLabsSttClientTest {

    @Test
    fun `texte renvoye et requete multipart conforme`() = runBlocking {
        sttServer().use { server ->
            val client = ElevenLabsSttClient(TEST_KEY, baseUrl = server.baseUrl)
            val text = client.transcribe(WAV).getOrThrow()

            assertEquals("Bonjour, ceci est un test.", text)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/speech-to-text", request.path)
            assertEquals(TEST_KEY, request.headers["xi-api-key"])
            assertTrue(request.headers["content-type"].orEmpty().startsWith("multipart/form-data"))
            assertTrue(request.body, request.body.contains("model_id"))
            assertTrue(request.body, request.body.contains("scribe_v1"))
            assertTrue(request.body, request.body.contains("audio.wav"))
            assertTrue(request.body, request.body.contains("RIFF"))
        }
    }

    @Test
    fun `402 devient PaymentRequired`() = runBlocking {
        sttServer(code = 402, body = """{"detail":{"status":"payment_required"}}""").use { server ->
            val result = ElevenLabsSttClient(TEST_KEY, baseUrl = server.baseUrl).transcribe(WAV)
            assertTrue(result.exceptionOrNull() is ApiError.PaymentRequired)
        }
    }

    @Test
    fun `cle absente echoue sans appel reseau`() = runBlocking {
        sttServer().use { server ->
            val result = ElevenLabsSttClient("", baseUrl = server.baseUrl).transcribe(WAV)
            assertTrue(result.exceptionOrNull() is ApiError.InvalidKey)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `audio trop court refuse localement`() = runBlocking {
        sttServer().use { server ->
            val result = ElevenLabsSttClient(TEST_KEY, baseUrl = server.baseUrl).transcribe(ByteArray(40))
            assertTrue(result.exceptionOrNull() is ApiError.BadResponse)
            assertTrue(server.requests.isEmpty())
        }
    }

    @Test
    fun `texte vide reste un succes vide`() = runBlocking {
        sttServer(body = """{"text":""}""").use { server ->
            val result = ElevenLabsSttClient(TEST_KEY, baseUrl = server.baseUrl).transcribe(WAV)
            assertEquals("", result.getOrThrow())
        }
    }
}