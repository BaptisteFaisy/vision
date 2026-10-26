package dev.sam3vision.core.net

import dev.sam3vision.core.model.SegmentResult
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport du client SAM 3.1 HTTP (`/segment` et `/health`), sans clé.
 *
 * Les tests historiques ([SamClientTest]) couvrent le parsing, le 400 et le timeout de
 * `health` ; cette batterie ajoute la matrice d'erreurs complète, les corps illisibles et
 * la forme exacte de la requête de segmentation.
 */
class SamContractTest {

    private fun client(url: String) = HttpSamClient(url)

    private suspend fun segment(url: String): Result<SegmentResult> =
        client(url).segment("QUJD", listOf("personne"))

    private val validSegment = """
        {"width":640,"height":480,"objects":[
          {"label":"personne","score":0.9,"box":[1,2,3,4],"mask_b64":"QUJD"}
        ]}
    """.trimIndent()

    @Test
    fun `segment est un POST JSON avec le User-Agent et sans cle`() = runBlocking {
        NetContract.server(body = validSegment).use { server ->
            segment(server.baseUrl).getOrThrow()

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/segment", request.path)
            assertEquals("application/json; charset=utf-8", request.headers["content-type"])
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertFalse("SAM 3.1 HTTP ne demande aucune clé", request.headers.containsKey("authorization"))
            val body = JSONObject(request.body)
            assertEquals("QUJD", body.getString("image_b64"))
            assertEquals("personne", body.getJSONArray("prompts").getString(0))
        }
    }

    @Test
    fun `401 devient InvalidKey`() = runBlocking {
        NetContract.assertCode("sam", 401, ApiError.InvalidKey::class.java, ::segment)
    }

    @Test
    fun `402 devient PaymentRequired`() = runBlocking {
        NetContract.assertCode("sam", 402, ApiError.PaymentRequired::class.java, ::segment)
    }

    @Test
    fun `429 devient RateLimited`() = runBlocking {
        NetContract.assertCode("sam", 429, ApiError.RateLimited::class.java, ::segment)
    }

    @Test
    fun `500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("sam", 500, ApiError.Http::class.java, ::segment)
    }

    @Test
    fun `un corps vide en 200 devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("sam", "", ::segment)
    }

    @Test
    fun `un corps non JSON devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("sam", "modal-http: invalid function call", ::segment)
    }

    @Test
    fun `un JSON tronque devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("sam", """{"width":640,"objects":[{"label":"per""", ::segment)
    }

    @Test
    fun `un delai depasse sur segment devient Timeout`() = runBlocking {
        NetContract.slowServer().use { server ->
            val result = HttpSamClient(server.baseUrl, NetContract.impatientClient())
                .segment("QUJD", null)

            NetContract.assertError(result, ApiError.Timeout::class.java, "sam lent")
        }
    }

    @Test
    fun `health est un GET identifie sur health`() = runBlocking {
        NetContract.server(body = """{"ok":true}""").use { server ->
            assertTrue(client(server.baseUrl).health().getOrThrow())

            val request = server.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
            assertEquals(USER_AGENT, request.headers["user-agent"])
            assertFalse(request.headers.containsKey("authorization"))
        }
    }

    @Test
    fun `health repond faux sur un 200 sans ok vrai`() = runBlocking {
        NetContract.server(body = """{"ok":false,"detail":"chargement"}""").use { server ->
            assertFalse(client(server.baseUrl).health().getOrThrow())
        }
    }

    @Test
    fun `health reste faux sur un corps illisible plutot que de lever`() = runBlocking {
        NetContract.server(body = "<html>pas du json</html>").use { server ->
            assertFalse("un health illisible vaut « pas prêt »", client(server.baseUrl).health().getOrThrow())
        }
    }
}