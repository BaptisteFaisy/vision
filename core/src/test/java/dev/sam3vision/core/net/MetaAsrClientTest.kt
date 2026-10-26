package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.audio.Wav
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val KEY = "cle-meta-de-test"

private fun client(server: TestHttpServer, key: String = KEY) = MetaAsrClient(server.baseUrl, key)

private fun oneSecondWav(): ByteArray {
    val pcm = ByteArray(Wav.SAMPLE_RATE * Wav.BYTES_PER_SAMPLE)
    return Wav.pcm16(pcm)
}

class MetaAsrClientTest {

    @Test
    fun `transcribe envoie un multipart request+audio et rend le texte`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"text":"bonjour le monde"}""") }
        try {
            val text = runBlocking { client(server).transcribe(oneSecondWav()) }.getOrThrow()
            assertEquals("bonjour le monde", text)
            val sent = server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/asr/transcribe", sent.path)
            assertEquals("Bearer $KEY", sent.headers["authorization"] ?: sent.headers["Authorization"])
            assertTrue(sent.headers["content-type"].orEmpty().startsWith("multipart/form-data"))
            assertTrue(sent.body.contains("name=\"request\""))
            assertTrue(sent.body.contains("\"model\":\"muse-voice-transcribe-1.0\""))
            assertTrue(sent.body.contains("\"audioEncoding\":\"WAV\""))
            assertTrue(sent.body.contains("name=\"audio\""))
            assertTrue(sent.body.contains("RIFF"))
            // OkHttp ajoute un Content-Length par part : Meta le refuse (400 malformed).
            assertTrue(!sent.body.contains("Content-Length:"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `transcribe accepte les champs imbriques et les segments`() {
        val nested = TestHttpServer { TestHttpServer.Response(200, """{"result":{"transcript":"salut"}}""") }
        val segmented = TestHttpServer {
            TestHttpServer.Response(200, """{"segments":[{"text":"un"},{"text":"deux"}]}""")
        }
        try {
            assertEquals("salut", runBlocking { client(nested).transcribe(oneSecondWav()) }.getOrThrow())
            assertEquals("un deux", runBlocking { client(segmented).transcribe(oneSecondWav()) }.getOrThrow())
        } finally {
            nested.close()
            segmented.close()
        }
    }

    @Test
    fun `un texte vide est un succes - le cas d'usage decidera`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"text":""}""") }
        try {
            assertEquals("", runBlocking { client(server).transcribe(oneSecondWav()) }.getOrThrow())
        } finally {
            server.close()
        }
    }

    @Test
    fun `402 devient PaymentRequired avec la facturation Meta en cause`() {
        val server = TestHttpServer {
            TestHttpServer.Response(402, """{"error":{"code":"billing_not_configured"}}""")
        }
        try {
            val error = runBlocking { client(server).transcribe(oneSecondWav()) }.exceptionOrNull()
            assertTrue(error is ApiError.PaymentRequired)
            assertTrue(error?.message.orEmpty().contains("402"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `401 devient InvalidKey`() {
        val server = TestHttpServer { TestHttpServer.Response(401, "{}") }
        try {
            val error = runBlocking { client(server).transcribe(oneSecondWav()) }.exceptionOrNull()
            assertTrue(error is ApiError.InvalidKey)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sans cle aucune requete ne part`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"text":"jamais"}""") }
        try {
            val error = runBlocking { client(server, key = "").transcribe(oneSecondWav()) }.exceptionOrNull()
            assertTrue(error is ApiError.InvalidKey)
            assertTrue(server.requests.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `un audio vide est refuse avant le reseau`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"text":"jamais"}""") }
        try {
            val error = runBlocking { client(server).transcribe(ByteArray(0)) }.exceptionOrNull()
            assertTrue(error is ApiError.BadResponse)
            assertTrue(server.requests.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `une reponse sans champ texte devient BadResponse`() {
        val server = TestHttpServer { TestHttpServer.Response(200, """{"ok":true}""") }
        try {
            val error = runBlocking { client(server).transcribe(oneSecondWav()) }.exceptionOrNull()
            assertTrue(error is ApiError.BadResponse)
        } finally {
            server.close()
        }
    }
}