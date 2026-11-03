package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tranche FROID (cold start, reprise, 404) : ce que le direct fait quand le backend dort
 * ou vient de mourir — budget froid qui couvre le réveil, `/health` patient qui attend,
 * 404 « app arrêtée » sans nouvelle tentative, statuts non typés reçus en trame.
 * Sans réseau ([ContractWsTransport], [TestHttpServer] local) ni GPU.
 */
class WsLiveColdStartTest {

    private val base = "https://sam.example"

    @Test
    fun `health sur app arretee donne BackendStopped sans nouvelle tentative`() = runBlocking {
        // Sonde de réveil sur une app Modal arrêtée : le 404 « invalid function call »
        // n'est pas transitoire (Errors.kt), donc un seul essai — la détection froide
        // (GpuMonitor → Down) ne mitraille pas un backend mort.
        NetContract.server(code = 404, body = "modal-http: invalid function call").use { server ->
            val result = HttpLiveClient(server.baseUrl, retryDelayMs = 0L).health()

            NetContract.assertError(result, ApiError.BackendStopped::class.java, "health 404 app arrêtée")
            assertEquals("pas de nouvelle tentative", 1, server.requests.size)
        }
    }

    @Test
    fun `health attend un backend froid dans son delai patient`() = runBlocking {
        // Conteneur qui se réveille (réponse à 500 ms) : le client de réveil (180 s)
        // l'attend, là où le budget court du direct (5 s) l'aurait déjà tué — le
        // pendant positif du health impatient de LiveContractTest.
        TestHttpServer {
            TestHttpServer.Response(
                200,
                """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""",
                delayMs = 500,
            )
        }.use { server ->
            val health = HttpLiveClient(server.baseUrl).health().getOrThrow()

            assertTrue("backend froid joint", health.ok)
        }
    }

    @Test
    fun `une reponse tardive passe en budget froid et expire en budget court`() = runBlocking {
        // Même réponse livrée à 300 ms : le budget froid (60 s, réveil Modal) la prend,
        // le budget court (100 ms, régime) a déjà abandonné — la preuve froide/chaud
        // de FIX-18, sans attendre un vrai réveil.
        val coldTransport = ContractWsTransport()
        val cold = WsLiveClient(base, coldTransport, 60_000, RecordingLiveClient(), binaryWs = false)
        val coldFrame = async { cold.live("QQ==", null, 640, 4) }
        awaitSent(coldTransport)
        delay(300)
        coldTransport.deliver(NetContract.frameReply(1))

        assertEquals(512, coldFrame.await().getOrThrow().width)
        cold.close()

        val shortTransport = ContractWsTransport()
        val short = WsLiveClient(base, shortTransport, 100, RecordingLiveClient(), binaryWs = false)
        val shortFrame = async { short.live("QQ==", null, 640, 4) }
        awaitSent(shortTransport)
        delay(300)
        shortTransport.deliver(NetContract.frameReply(1))

        NetContract.assertError(shortFrame.await(), ApiError.Timeout::class.java, "budget court")
        short.close()
    }

    /**
     * Le serveur annonce ses erreurs **dans** la trame, y compris des statuts hors du
     * trio typé (401/402/429) : un 404 ou un 503 reste un [ApiError.BadResponse] dont
     * le message porte le statut et le détail — même barème qu'en `POST /live`.
     */
    @Test
    fun `une erreur 404 ou 503 en trame WS devient BadResponse avec son statut`() = runBlocking {
        listOf(404, 503).forEach { status ->
            val transport = ContractWsTransport { json ->
                listOf(NetContract.errorReply(json.getInt("id"), status = status))
            }
            val result = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)
                .live("QQ==", null, 640, 4)

            NetContract.assertError(result, ApiError.BadResponse::class.java, "erreur de trame $status")
            assertTrue(
                "le statut doit rester lisible : ${NetContract.errorOf(result).message}",
                NetContract.errorOf(result).message!!.contains(status.toString()),
            )
        }
    }

    /** Attend que la frame soit réellement partie, sans sommeil fixe. */
    private suspend fun awaitSent(transport: ContractWsTransport) {
        withTimeout(2_000) { while (transport.sent.isEmpty()) delay(5) }
    }
}
