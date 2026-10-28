package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Envoi vidéo à SAM 3.1 : multipart, réponses tolérantes, erreurs typées. */
class VideoClientTest {

    private var responseCode = 200
    private var responseBody = """{"text":"Un chat traverse la pièce."}"""

    private val server = TestHttpServer { request ->
        when (request.path) {
            "/video" -> TestHttpServer.Response(responseCode, responseBody)
            "/health" -> TestHttpServer.Response(200, """{"ok":true}""")
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    private val video: File = Files.createTempFile("sam-video", ".mp4").toFile().apply {
        writeBytes(ByteArray(64) { it.toByte() })
    }

    @After
    fun tearDown() {
        server.close()
        video.delete()
    }

    @Test
    fun `l envoi est un multipart avec le fichier le modele et la consigne`() = runBlocking {
        val result = HttpSam31VideoClient(server.baseUrl).sendVideo(video, "Décris la scène.")
        assertTrue(result.isSuccess)
        assertEquals("Un chat traverse la pièce.", result.getOrNull())
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/video", request.path)
        assertTrue(request.headers["content-type"].orEmpty().startsWith("multipart/form-data"))
        assertTrue(request.body.contains("name=\"video\""))
        assertTrue(request.body.contains("sam3.1-video"))
        assertTrue(request.body.contains("Décris la scène."))
    }

    @Test
    fun `une reponse texte brut est reprise telle quelle`() = runBlocking {
        responseBody = "Une voiture roule."
        val result = HttpSam31VideoClient(server.baseUrl).sendVideo(video, null)
        assertEquals("Une voiture roule.", result.getOrNull())
    }

    @Test
    fun `un 500 devient une erreur typee Http`() = runBlocking {
        responseCode = 500
        responseBody = """{"detail":"boom"}"""
        val failure = HttpSam31VideoClient(server.baseUrl).sendVideo(video, null).exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(500, (failure as ApiError.Http).code)
    }

    @Test
    fun `un 404 de route manquante est une erreur Http 404 interrogeable`() = runBlocking {
        responseCode = 404
        responseBody = """{"detail":"Not Found"}"""
        val failure = HttpSam31VideoClient(server.baseUrl).sendVideo(video, null).exceptionOrNull()
        assertTrue(failure is ApiError.Http)
        assertEquals(404, (failure as ApiError.Http).code)
        assertTrue(ApiError.isRouteMissing(failure))
    }

    @Test
    fun `un 404 invalid function call reste un BackendStopped sans repli photo`() = runBlocking {
        responseCode = 404
        responseBody = "modal-http: invalid function call"
        val failure = HttpSam31VideoClient(server.baseUrl).sendVideo(video, null).exceptionOrNull()
        assertTrue(failure is ApiError.BackendStopped)
        assertTrue(failure?.let { !ApiError.isRouteMissing(it) } == true)
    }

    @Test
    fun `isRouteMissing refuse les autres echecs`() {
        assertFalse(ApiError.isRouteMissing(ApiError.Http(500, "boom")))
        assertFalse(ApiError.isRouteMissing(ApiError.RateLimited()))
        assertFalse(ApiError.isRouteMissing(ApiError.InvalidKey("endpoint SAM 3.1 vide")))
    }

    @Test
    fun `un endpoint vide echoue sans appel reseau`() = runBlocking {
        val failure = HttpSam31VideoClient("").sendVideo(video, null).exceptionOrNull()
        assertTrue(failure is ApiError.InvalidKey)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `le mode on-device refuse l envoi cloud sans appel reseau`() = runBlocking {
        val failure = HttpSam31VideoClient(server.baseUrl, onDeviceMode = true).sendVideo(video, null)
            .exceptionOrNull()
        assertTrue(failure is ApiError.BadResponse)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `une video absente est refusee avant le reseau`() = runBlocking {
        val failure = HttpSam31VideoClient(server.baseUrl).sendVideo(File("/tmp/absent-xyz.mp4"), null)
            .exceptionOrNull()
        assertTrue(failure is ApiError.BadResponse)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `health renvoie vrai puis faux`() = runBlocking {
        assertTrue(HttpSam31VideoClient(server.baseUrl).health().getOrDefault(false))
        assertFalse(HttpSam31VideoClient("").health().getOrDefault(true))
    }

    @Test
    fun `un resume trop long est tronque`() {
        val long = "a".repeat(1_500)
        val summary = summarize("""{"text":"$long"}""").getOrThrow()
        assertEquals(601, summary.length) // 600 caractères + l'ellipse
    }

    @Test
    fun `un JSON sans champ texte n est pas un resume`() {
        val error = summarize("""{"detail":"modele inconnu"}""").exceptionOrNull()
        assertTrue(error.toString(), error is ApiError.BadResponse)
    }
}
