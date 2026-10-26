package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de transport de `POST /live` et `GET /health` du direct.
 *
 * [LiveClientTest] verrouille déjà le parsing, le 400, le corps illisible et le timeout ;
 * cette batterie ajoute la matrice d'erreurs complète (401/402/429/500), le 404 « app
 * arrêtée », le corps vide, le JSON tronqué et la forme exacte des deux requêtes.
 */
class LiveContractTest {

    private fun client(url: String) = HttpLiveClient(url)

    private suspend fun live(url: String): Result<LiveFrame> = client(url).live("QUJD", null, 640, 4)

    @Test
    fun `live est un POST JSON identifie par le User-Agent du direct`() = runBlocking {
        NetContract.server(body = NetContract.frameReply()).use { server ->
            live(server.baseUrl).getOrThrow()

            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/live", request.path)
            assertEquals("application/json; charset=utf-8", request.headers["content-type"])
            assertEquals(LIVE_USER_AGENT, request.headers["user-agent"])
            assertFalse("le direct ne porte aucune clé", request.headers.containsKey("authorization"))
            val body = JSONObject(request.body)
            assertEquals("QUJD", body.getString("image_b64"))
            assertEquals(640, body.getInt("work_edge"))
            assertEquals(4, body.getInt("max_objects"))
            assertEquals("bits", body.getString("mask_encoding"))
        }
    }

    @Test
    fun `401 devient InvalidKey`() = runBlocking {
        NetContract.assertCode("live", 401, ApiError.InvalidKey::class.java, ::live)
    }

    @Test
    fun `402 devient PaymentRequired`() = runBlocking {
        NetContract.assertCode("live", 402, ApiError.PaymentRequired::class.java, ::live)
    }

    @Test
    fun `429 devient RateLimited`() = runBlocking {
        NetContract.assertCode("live", 429, ApiError.RateLimited::class.java, ::live)
    }

    @Test
    fun `500 reste une erreur Http 500`() = runBlocking {
        NetContract.assertCode("live", 500, ApiError.Http::class.java, ::live)
    }

    @Test
    fun `un 404 de Modal app arretee donne BackendStopped`() = runBlocking {
        NetContract.server(code = 404, body = "modal-http: invalid function call").use { server ->
            val error = live(server.baseUrl).exceptionOrNull()
            assertTrue("attendu BackendStopped, obtenu $error", error is ApiError.BackendStopped)
        }
    }

    @Test
    fun `un corps vide en 200 devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("live", "", ::live)
    }

    @Test
    fun `un JSON tronque devient BadResponse`() = runBlocking {
        NetContract.assertBadBody("live", """{"width":512,"objects":[{"label":"per""", ::live)
    }

    @Test
    fun `health est un GET identifie sur health`() = runBlocking {
        NetContract.server(body = """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""").use { server ->
            assertEquals("sam3.1", client(server.baseUrl).health().getOrThrow().model)

            val request = server.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
            assertEquals(LIVE_USER_AGENT, request.headers["user-agent"])
        }
    }

    @Test
    fun `health leve BadResponse sur un corps illisible`() = runBlocking {
        NetContract.server(body = "modal-http: invalid function call").use { server ->
            val result = client(server.baseUrl).health()
            NetContract.assertError(result, ApiError.BadResponse::class.java, "health illisible")
        }
    }

    @Test
    fun `health utilise son propre budget de reveil et leve Timeout`() = runBlocking {
        NetContract.slowServer().use { server ->
            val result = HttpLiveClient(server.baseUrl, wakeClient = NetContract.impatientClient())
                .health()

            NetContract.assertError(result, ApiError.Timeout::class.java, "health lent")
        }
    }
}