package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport du moteur Meta (`POST /asr/transcribe`).
 *
 * [MetaAsrClientTest] verrouille déjà le multipart écrit à la main, les champs imbriqués,
 * le 401/402, l'audio vide et l'absence de champ texte. Ici : 400/429/500, corps vide,
 * corps non JSON, JSON tronqué, timeout et User-Agent.
 */
class MetaAsrContractTest {

    private fun client(url: String) = MetaAsrClient(url, NetContract.KEY)

    private suspend fun transcribe(url: String): Result<String> = client(url).transcribe(NetContract.AUDIO)

    @Test
    fun `la requete est un POST multipart authentifie et identifie`() = runBlocking {
        NetContract.server(body = """{"text":"bonjour"}""").use { server ->
            assertEquals("bonjour", transcribe(server.baseUrl).getOrThrow())

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals(MetaAsrClient.TRANSCRIBE_PATH, request.path)
            assertEquals("Bearer ${NetContract.KEY}", request.headers["authorization"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            val contentType = request.headers["content-type"].orEmpty()
            assertTrue("frontière multipart attendue : $contentType", contentType.startsWith("multipart/form-data; boundary="))
        }
    }

    @Test
    fun `400 reste une erreur Http 400`() = runBlocking {
        NetContract.assertCode("meta", 400, ApiError.Http::class.java, ::transcribe)
    }

    @Test
    fun `429 devient RateLimited`() = runBlocking {
        NetContract.assertCode("meta", 429, ApiError.RateLimited::class.java, ::transcribe)
    }

    @Test
    fun `500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("meta", 500, ApiError.Http::class.java, ::transcribe)
    }

    @Test
    fun `un corps vide en 200 devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("meta", "", ::transcribe)
    }

    /**
     * Un corps qui n'est pas du JSON n'est pas une transcription : une erreur HTML servie
     * en 200 ne doit jamais devenir la dictée affichée.
     */
    @Test
    fun `un corps non JSON devient BadResponse`() = runBlocking {
        NetContract.server(body = "<html>Bad Gateway</html>", contentType = "text/html").use { server ->
            NetContract.assertError(transcribe(server.baseUrl), ApiError.BadResponse::class.java, "meta html")
        }
    }

    /** Même règle sur un JSON tronqué : illisible ⇒ erreur, jamais le corps affiché. */
    @Test
    fun `un JSON tronque devient BadResponse`() = runBlocking {
        NetContract.server(body = """{"text":"coup""").use { server ->
            NetContract.assertError(transcribe(server.baseUrl), ApiError.BadResponse::class.java, "meta tronqué")
        }
    }

    @Test
    fun `un delai depasse devient Timeout`() = runBlocking {
        NetContract.slowServer().use { server ->
            val result = MetaAsrClient(
                server.baseUrl,
                NetContract.KEY,
                http = NetContract.impatientClient(),
            ).transcribe(NetContract.AUDIO)

            NetContract.assertError(result, ApiError.Timeout::class.java, "meta lent")
        }
    }
}