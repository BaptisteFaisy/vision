package dev.sam3vision.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat du direct par WebSocket (`/live/ws`) vu de la fabrique, là où [WsLiveClientTest]
 * teste le client lui-même : schéma d'URL pour un backend en `http://`, envoi refusé,
 * délégué HTTP réel du `/health`, typage des erreurs annoncées **dans** la trame et
 * corrélation de plusieurs frames en vol.
 */
class WsLiveContractTest {

    private val base = "https://sam.example"

    @Test
    fun `un backend en http simple devient un handshake ws sur live ws`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient("http://127.0.0.1:9", transport, 200, RecordingLiveClient(), binaryWs = false)

        client.live("QQ==", null, 640, 4)

        assertEquals("ws://127.0.0.1:9/live/ws", transport.openedUrl)
    }

    @Test
    fun `un envoi refuse par le socket donne Network sans attendre le delai`() = runBlocking {
        val transport = ContractWsTransport().apply { sendResult = false }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val result = client.live("QQ==", null, 640, 4)

        NetContract.assertError(result, ApiError.Network::class.java, "envoi refusé")
        assertEquals("le message doit quand même avoir été tenté", 1, transport.sent.size)
    }

    @Test
    fun `une fermeture propre du socket donne Network et non un type inconnu`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(null)      // close() côté serveur, sans exception
            emptyList()
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val result = client.live("QQ==", null, 640, 4)

        NetContract.assertError(result, ApiError.Network::class.java, "socket fermé")
        val error = NetContract.errorOf(result)
        assertTrue("le message doit rester actionnable : ${error.message}", error.message!!.contains("closed"))
    }

    @Test
    fun `le health du client WS traverse le vrai HTTP jusqu au serveur`() = runBlocking {
        NetContract.server(body = """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""").use { server ->
            val client = WsLiveClient(
                server.baseUrl,
                ContractWsTransport(),
                1_000,
                HttpLiveClient(server.baseUrl),
            )

            assertEquals("A100", client.health().getOrThrow().gpu)
            val request = server.requests.single()
            assertEquals("GET", request.method)
            assertEquals("/health", request.path)
            assertEquals(LIVE_USER_AGENT, request.headers["user-agent"])
        }
    }

    @Test
    fun `un 401 du health reste InvalidKey a travers le client WS`() = runBlocking {
        NetContract.server(code = 401, body = """{"detail":"bad key"}""").use { server ->
            val client = WsLiveClient(
                server.baseUrl,
                ContractWsTransport(),
                1_000,
                HttpLiveClient(server.baseUrl),
            )

            NetContract.assertError(client.health(), ApiError.InvalidKey::class.java, "health 401")
        }
    }

    /**
     * Le serveur annonce ses erreurs **dans** la trame JSON (`{"id":…,"error":{"status":402,…}}`).
     * Le statut suit le **même barème qu'en `POST /live`** : un 402 reçu en WebSocket est un
     * [ApiError.PaymentRequired], pas un `BadResponse` générique.
     */
    @Test
    fun `une erreur 402 recue en trame WS devient PaymentRequired`() = runBlocking {
        val transport = ContractWsTransport { json ->
            listOf(NetContract.errorReply(json.getInt("id"), status = 402, detail = "credits epuises"))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val result = client.live("QQ==", null, 640, 4)
        NetContract.assertError(result, ApiError.PaymentRequired::class.java, "erreur de trame 402")
        assertTrue(
            "le statut doit rester lisible : ${NetContract.errorOf(result).message}",
            NetContract.errorOf(result).message!!.contains("402"),
        )
    }

    /** Même barème pour les deux autres refus typés, sur le même transport. */
    @Test
    fun `les erreurs 401 et 429 recues en trame WS sont typees comme en POST`() = runBlocking {
        listOf(401 to ApiError.InvalidKey::class.java, 429 to ApiError.RateLimited::class.java)
            .forEach { (status, type) ->
                val transport = ContractWsTransport { json ->
                    listOf(NetContract.errorReply(json.getInt("id"), status = status))
                }
                val result = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)
                    .live("QQ==", null, 640, 4)

                NetContract.assertError(result, type, "erreur de trame $status")
                assertTrue(
                    "le statut doit rester lisible : ${NetContract.errorOf(result).message}",
                    NetContract.errorOf(result).message!!.contains(status.toString()),
                )
            }
    }

    @Test
    fun `trois frames en vol recoivent chacune la bonne reponse quel que soit l ordre`() = runBlocking {
        val transport = ContractWsTransport()      // silencieux : rien ne répond pendant `send`
        val client = WsLiveClient(base, transport, 2_000, RecordingLiveClient(), binaryWs = false)

        val frames = coroutineScope {
            val jobs = (1..3).map { id -> async { client.live("QQ==", null, 640, id) } }
            awaitInFlight(transport, expected = 3)
            // Le serveur répond à l'envers : la corrélation se fait par `id`, jamais par l'ordre.
            transport.deliver(NetContract.frameReply(id = 3, width = 300))
            transport.deliver(NetContract.frameReply(id = 2, width = 200))
            transport.deliver(NetContract.frameReply(id = 1, width = 100))
            jobs.awaitAll()
        }

        assertEquals(listOf(100, 200, 300), frames.map { it.getOrThrow().width }.sorted())
        assertEquals(3, transport.sent.size)
    }

    @Test
    fun `le client avec withDepth envoie le champ dans la trame`() = runBlocking {
        val transport = ContractWsTransport { json ->
            listOf(NetContract.frameReply(json.getInt("id")))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), withDepth = true, binaryWs = false)

        client.live("QQ==", null, 640, 4)

        val sent = org.json.JSONObject(transport.sent.single())
        assertTrue("with_depth dans la trame", sent.getBoolean("with_depth"))
    }

    @Test
    fun `une mediane depth dans la reponse est lue sur l objet`() = runBlocking {
        val transport = ContractWsTransport { json ->
            listOf(NetContract.frameReply(json.getInt("id"), depth = 0.75f))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val frame = client.live("QQ==", null, 640, 4).getOrThrow()

        assertEquals(0.75f, frame.objects.single().depth!!, 0.001f)
    }

    @Test
    fun `sans cle depth la mediane est nulle (ancien backend)`() = runBlocking {
        val transport = ContractWsTransport { json ->
            listOf(NetContract.frameReply(json.getInt("id")))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val frame = client.live("QQ==", null, 640, 4).getOrThrow()

        assertNull(frame.objects.single().depth)
    }

    /** Attend que [expected] frames soient réellement en vol, sans sommeil fixe. */
    private suspend fun awaitInFlight(transport: ContractWsTransport, expected: Int) {
        var spins = 0
        while (transport.sent.size < expected && spins < 500) {
            yield()
            spins++
        }
        assertEquals("$expected frames devaient être en vol", expected, transport.sent.size)
    }
}