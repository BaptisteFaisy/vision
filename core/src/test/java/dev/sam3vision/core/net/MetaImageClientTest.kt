package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val IMAGE_KEY = "cle-meta-de-test"

/** Faux JPEG : le marqueur suffit, le serveur de test ne décode rien. */
private fun fakeJpeg(): ByteArray = "JFIF-fausse-photo".toByteArray(Charsets.UTF_8)

/** PNG 1×1 : seules les 8 octets magiques sont vérifiés. */
private const val TINY_PNG_B64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

private fun imageClient(server: TestHttpServer, key: String = IMAGE_KEY) = MetaImageClient(server.baseUrl, key)

class MetaImageClientTest {

    @Test
    fun `edits envoie photo+prompt et rend le PNG`() {
        val server = TestHttpServer {
            TestHttpServer.Response(200, """{"created":1,"data":[{"b64_json":"$TINY_PNG_B64"}]}""")
        }
        try {
            val png = runBlocking { imageClient(server).editToPictogram(fakeJpeg(), "médicaments") }.getOrThrow()
            assertEquals(0x89.toByte(), png[0])
            assertEquals(0x50.toByte(), png[1])
            assertEquals(0x4E.toByte(), png[2])
            assertEquals(0x47.toByte(), png[3])
            val sent = server.requests.single()
            assertEquals("POST", sent.method)
            assertEquals("/images/edits", sent.path)
            assertEquals("Bearer $IMAGE_KEY", sent.headers["authorization"] ?: sent.headers["Authorization"])
            assertTrue(sent.headers["content-type"].orEmpty().startsWith("multipart/form-data"))
            assertTrue(sent.body.contains("name=\"model\""))
            assertTrue(sent.body.contains("muse-image-1.0"))
            assertTrue(sent.body.contains("name=\"prompt\""))
            assertTrue(sent.body.contains("pictogram"))
            assertTrue(sent.body.contains("médicaments"))
            assertTrue(sent.body.contains("name=\"image\""))
            assertTrue(sent.body.contains("JFIF-fausse-photo"))
            assertTrue(!sent.body.contains("Content-Length:"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `cle vide echoue sans requete`() {
        val server = TestHttpServer { TestHttpServer.Response(200, "{}") }
        try {
            val failure = runBlocking { imageClient(server, "").editToPictogram(fakeJpeg(), "") }.exceptionOrNull()
            assertTrue(failure is ApiError.InvalidKey)
            assertTrue(server.requests.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun `402 devient PaymentRequired`() {
        val server = TestHttpServer { TestHttpServer.Response(402, """{"error":{"code":"billing"}}""") }
        try {
            val failure = runBlocking { imageClient(server).editToPictogram(fakeJpeg(), "") }.exceptionOrNull()
            assertTrue(failure is ApiError.PaymentRequired)
        } finally {
            server.close()
        }
    }

    @Test
    fun `image absente ou url seule devient BadResponse`() {
        val missing = TestHttpServer { TestHttpServer.Response(200, """{"created":1,"data":[]}""") }
        val urlOnly = TestHttpServer {
            TestHttpServer.Response(200, """{"created":1,"data":[{"url":"https://x/y.png"}]}""")
        }
        try {
            val noImage = runBlocking { imageClient(missing).editToPictogram(fakeJpeg(), "") }.exceptionOrNull()
            assertTrue(noImage is ApiError.BadResponse)
            val noB64 = runBlocking { imageClient(urlOnly).editToPictogram(fakeJpeg(), "") }.exceptionOrNull()
            assertTrue(noB64 is ApiError.BadResponse)
        } finally {
            missing.close()
            urlOnly.close()
        }
    }

    @Test
    fun `base64 invalide devient BadResponse`() {
        val server = TestHttpServer {
            TestHttpServer.Response(200, """{"created":1,"data":[{"b64_json":"!!!"}]}""")
        }
        try {
            val failure = runBlocking { imageClient(server).editToPictogram(fakeJpeg(), "") }.exceptionOrNull()
            assertTrue(failure is ApiError.BadResponse)
        } finally {
            server.close()
        }
    }
}
