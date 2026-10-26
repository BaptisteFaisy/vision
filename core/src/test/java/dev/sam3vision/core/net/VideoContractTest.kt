package dev.sam3vision.core.net

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport de l'envoi vidéo à SAM 3.1 (`POST /video`, multipart).
 *
 * [VideoClientTest] verrouille déjà le multipart, la reprise du texte brut, le 500, les
 * refus locaux et la troncature du résumé. Ici : 400/401/402/429, timeout, en-têtes
 * réels, réponse vide et **JSON sans champ connu** (trou documenté), plus `health`.
 */
class VideoContractTest {

    private val video: File = Files.createTempFile("contrat-video", ".mp4").toFile().apply {
        writeBytes(ByteArray(64) { it.toByte() })
    }

    @After
    fun tearDown() {
        video.delete()
    }

    private fun client(url: String) = HttpSam31VideoClient(url)

    private suspend fun send(url: String): Result<String> = client(url).sendVideo(video, "Décris.")

    @Test
    fun `l envoi est un POST multipart identifie`() = runBlocking {
        NetContract.server(body = """{"text":"un chat"}""").use { server ->
            assertEquals("un chat", send(server.baseUrl).getOrThrow())

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals(HttpSam31VideoClient.DEFAULT_PATH, request.path)
            assertEquals(USER_AGENT, request.headers["user-agent"])
            val contentType = request.headers["content-type"].orEmpty()
            assertTrue("frontière multipart attendue : $contentType", contentType.startsWith("multipart/form-data; boundary="))
            assertTrue(request.body.contains("name=\"video\""))
            assertTrue(request.body.contains("name=\"prompt\""))
        }
    }

    @Test
    fun `400 reste une erreur Http 400`() = runBlocking {
        NetContract.assertCode("video", 400, ApiError.Http::class.java, ::send)
    }

    @Test
    fun `401 devient InvalidKey`() = runBlocking {
        NetContract.assertCode("video", 401, ApiError.InvalidKey::class.java, ::send)
    }

    @Test
    fun `402 devient PaymentRequired`() = runBlocking {
        NetContract.assertCode("video", 402, ApiError.PaymentRequired::class.java, ::send)
    }

    @Test
    fun `429 devient RateLimited`() = runBlocking {
        NetContract.assertCode("video", 429, ApiError.RateLimited::class.java, ::send)
    }

    @Test
    fun `une reponse vide devient un message de remplacement`() = runBlocking {
        NetContract.server(body = "").use { server ->
            assertEquals("réponse vide de SAM 3.1", send(server.baseUrl).getOrThrow())
        }
    }

    /**
     * Un JSON de diagnostic (`{"detail":…}` servi en 200) n'est pas un résumé : il doit
     * devenir une erreur, pas être affiché brut à l'utilisateur.
     */
    @Test
    fun `un JSON sans champ connu devient BadResponse`() = runBlocking {
        NetContract.server(body = """{"detail":"modele inconnu"}""").use { server ->
            val result = send(server.baseUrl)
            NetContract.assertError(result, ApiError.BadResponse::class.java, "video detail")
            assertTrue(
                "le diagnostic reste lisible : ${NetContract.errorOf(result).message}",
                NetContract.errorOf(result).message!!.contains("modele inconnu"),
            )
        }
    }

    @Test
    fun `un delai depasse devient Timeout`() = runBlocking {
        NetContract.slowServer().use { server ->
            val lent = HttpSam31VideoClient(server.baseUrl, client = NetContract.impatientClient())
            NetContract.assertError(lent.sendVideo(video, null), ApiError.Timeout::class.java, "video lente")
        }
    }

    @Test
    fun `health est un GET identifie sur health`() = runBlocking {
        NetContract.server(body = """{"ok":true,"model":"sam3.1-video"}""").use { server ->
            assertTrue(client(server.baseUrl).health().getOrThrow())

            val request = server.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
            assertEquals(USER_AGENT, request.headers["user-agent"])
        }
    }

    @Test
    fun `health reste faux sur un 200 sans ok vrai`() = runBlocking {
        NetContract.server(body = """{"ok":false}""").use { server ->
            assertFalse(client(server.baseUrl).health().getOrThrow())
        }
    }
}