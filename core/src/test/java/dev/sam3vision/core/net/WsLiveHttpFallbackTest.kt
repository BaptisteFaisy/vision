package dev.sam3vision.core.net

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repli automatique WebSocket → POST /live, testé **sans réseau** : quand le socket ne
 * peut pas servir une frame (ouverture refusée, envoi refusé, fermeture en vol), elle
 * part via le délégué HTTP — repli collant, re-sonde WS périodique, retour au socket
 * dès qu'il revit. Les non-déclencheurs sont verrouillés ici aussi : délai dépassé
 * (serveur lent, pas de double inférence), endpoint inutilisable, hors ligne connu,
 * backoff en cours et session fermée.
 */
class WsLiveHttpFallbackTest {

    private val base = "https://sam.example"

    /** Compte les ouvertures et refuse tout : la preuve « pas de handshake mort par frame ». */
    private class CountingRefusedTransport : WsTransport {
        var opens = 0

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            return false
        }

        override fun send(text: String): Boolean = false

        override fun sendBytes(bytes: ByteArray): Boolean = false

        override fun close() = Unit
    }

    /** Faux HTTP qui distingue `live` et `liveBytes` (le pont par défaut les confondrait). */
    private class HttpProbeClient(var result: Result<LiveFrame> = Result.success(SAMPLE_FRAME)) : LiveClient {
        var liveCalls = 0
        var liveBytesCalls = 0
        var lastBytes: ByteArray? = null

        override suspend fun live(
            jpegB64: String,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            liveCalls += 1
            return result
        }

        override suspend fun liveBytes(
            jpeg: ByteArray,
            prompts: List<String>?,
            workEdge: Int,
            maxObjects: Int,
        ): Result<LiveFrame> {
            liveBytesCalls += 1
            lastBytes = jpeg
            return result
        }

        override suspend fun health(): Result<LiveHealth> = Result.success(SAMPLE_HEALTH)
    }

    @Test
    fun `une ouverture refusee sert la frame en POST`() = runBlocking {
        val transport = ContractWsTransport().apply { openResult = false }
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false)

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertTrue("rien sur le socket", transport.sent.isEmpty())
        assertEquals(1, http.liveCalls)
        assertTrue("le repli colle", client.httpFallbackActive)
        assertFalse(client.isConnected)
    }

    @Test
    fun `un envoi refuse sert la frame en POST`() = runBlocking {
        val transport = ContractWsTransport().apply { sendResult = false }
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false)

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals("l'envoi WS a bien été tenté", 1, transport.sent.size)
        assertEquals(1, http.liveCalls)
        assertTrue("le repli colle", client.httpFallbackActive)
        assertEquals(WsLinkState.Degraded, client.linkState.value)
        client.close()
    }

    @Test
    fun `le repli colle sans handshake mort par frame`() = runBlocking {
        val transport = CountingRefusedTransport()
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false)

        repeat(4) { client.live("QQ==", null, 640, 4).getOrThrow() }

        assertEquals("un seul handshake pour 4 frames", 1, transport.opens)
        assertEquals(4, http.liveCalls)
        assertTrue(client.httpFallbackActive)
    }

    @Test
    fun `le WS revit a la re-sonde et le repli se decolle`() = runBlocking {
        val transport = ContractWsTransport().apply { openResult = false }
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false)

        client.live("QQ==", null, 640, 4).getOrThrow()
        assertTrue("le repli colle après l'ouverture refusée", client.httpFallbackActive)

        // Le serveur revit : le WS accepte et répond à nouveau.
        transport.openResult = true
        transport.answerWith { json -> listOf(NetContract.frameReply(json.getInt("id"))) }

        // 8 frames en POST direct (1 + 8 = 9 < 10 : aucune re-sonde).
        repeat(8) { client.live("QQ==", null, 640, 4).getOrThrow() }
        assertEquals(9, http.liveCalls)
        assertTrue("aucun essai WS avant la re-sonde", transport.sent.isEmpty())

        // 10e frame HTTP : re-sonde — le WS sert, le repli se décolle.
        val frame = client.live("QQ==", null, 640, 4).getOrThrow()
        assertEquals(512, frame.width)
        assertEquals(1, transport.sent.size)
        assertEquals("aucun POST pour la frame re-sondée", 9, http.liveCalls)
        assertFalse(client.httpFallbackActive)

        // La suivante repart direct en WS, sans POST.
        client.live("QQ==", null, 640, 4).getOrThrow()
        assertEquals(2, transport.sent.size)
        assertEquals(9, http.liveCalls)
        client.close()
    }

    @Test
    fun `si le POST echoue aussi l erreur HTTP remonte et le repli colle`() = runBlocking {
        val transport = CountingRefusedTransport()
        val http = HttpProbeClient(Result.failure(ApiError.Network("POST /live : 503 serveur saturé")))
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false)

        val failure = client.live("QQ==", null, 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("POST /live : 503 serveur saturé", failure?.message)
        assertTrue("le repli colle même quand le POST échoue", client.httpFallbackActive)

        client.live("QQ==", null, 640, 4)
        assertEquals("pas de second handshake", 1, transport.opens)
        assertEquals(2, http.liveCalls)
    }

    @Test
    fun `liveBytes replie appelle liveBytes du delegue HTTP`() = runBlocking {
        val transport = CountingRefusedTransport()
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 1_000, http)

        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)
        val frame = client.liveBytes(jpeg, listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals("octets bruts transmis, sans détour base64", 1, http.liveBytesCalls)
        assertEquals(0, http.liveCalls)
        assertTrue(http.lastBytes!!.contentEquals(jpeg))
    }

    @Test
    fun `un delai depasse ne replie pas en HTTP`() = runBlocking {
        val transport = ContractWsTransport()
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, timeoutMs = 100, http = http, binaryWs = false)

        val failure = client.live("QQ==", null, 640, 4).exceptionOrNull()

        assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        assertEquals("serveur lent : pas de seconde inférence en POST", 0, http.liveCalls)
        assertFalse("pas de repli collant sur délai", client.httpFallbackActive)
        client.close()
    }

    @Test
    fun `hors ligne le repli colle sans appeler le POST`() = runBlocking {
        val transport = CountingRefusedTransport()
        val http = HttpProbeClient()
        val net = FakeNetworkState()
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false, net = net)

        client.live("QQ==", null, 640, 4).getOrThrow()
        assertEquals(1, http.liveCalls)

        net.available = false
        val failure = client.live("QQ==", null, 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(failure?.message.orEmpty().contains("hors ligne"))
        assertEquals("aucun POST hors ligne", 1, http.liveCalls)
        assertEquals("aucun handshake hors ligne", 1, transport.opens)
    }

    @Test
    fun `un endpoint inutilisable ne replie pas en POST`() = runBlocking {
        val transport = ContractWsTransport()
        val http = HttpProbeClient()
        val client = WsLiveClient("pas-une-url", transport, 1_000, http, binaryWs = false)

        val failure = client.live("QQ==", null, 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(failure?.message.orEmpty().contains("Settings"))
        assertEquals("aucun POST sur endpoint inutilisable", 0, http.liveCalls)
        assertFalse("socket jamais ouvert", transport.opened)
    }

    @Test
    fun `close annule le repli et ne replie pas les frames en vol`() = runBlocking {
        val transport = ContractWsTransport()
        val http = HttpProbeClient()
        val client = WsLiveClient(base, transport, 5_000, http, binaryWs = false)

        val frame = async { client.live("QQ==", null, 640, 4) }
        withTimeout(2_000) { while (transport.sent.isEmpty()) delay(5) }
        client.close()

        val failure = frame.await().exceptionOrNull()
        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(failure?.message.orEmpty().contains("closed"))
        assertEquals("aucun POST après close", 0, http.liveCalls)
        assertFalse("repli annulé", client.httpFallbackActive)
    }

    @Test
    fun `la politique sonde une frame sur N puis se decolle au succes`() {
        val policy = WsHttpFallback(probeEvery = 3)

        assertTrue("jamais replié : WS direct", policy.routeWs())
        assertFalse(policy.active)

        policy.onWsFailed()
        assertTrue(policy.active)
        assertFalse(policy.routeWs())
        assertFalse(policy.routeWs())
        assertTrue("3e frame HTTP : re-sonde", policy.routeWs())

        policy.onWsServed()
        assertFalse(policy.active)
        assertTrue(policy.routeWs())
    }

    @Test
    fun `reset annule le repli colle`() {
        val policy = WsHttpFallback(probeEvery = 3)
        policy.onWsFailed()
        policy.reset()
        assertFalse(policy.active)
        assertTrue(policy.routeWs())
    }

    @Test
    fun `un refus de backoff ne part pas en HTTP`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = ContractWsTransport().apply { openResult = false }
        val http = HttpProbeClient(Result.failure(ApiError.Network("POST /live : injoignable")))
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false, link = link, httpFallback = WsHttpFallback(probeEvery = 1))

        assertTrue(client.live("QQ==", null, 640, 4).exceptionOrNull() is ApiError.Network)
        assertTrue(client.live("QQ==", null, 640, 4).exceptionOrNull() is ApiError.Network)
        assertEquals(2, link.attemptCount)

        val refused = client.live("QQ==", null, 640, 4).exceptionOrNull()
        assertTrue("attendu Network, obtenu $refused", refused is ApiError.Network)
        assertTrue(refused?.message.orEmpty().contains("reconnexion"))
        assertEquals("le backoff espace le WS, le POST n'est pas tenté", 2, http.liveCalls)
    }

    @Test
    fun `le repli traverse le vrai POST live jusqu au serveur`() = runBlocking {
        NetContract.server(body = NetContract.frameReply(id = null)).use { server ->
            val transport = CountingRefusedTransport()
            val client = WsLiveClient(server.baseUrl, transport, 1_000, HttpLiveClient(server.baseUrl), binaryWs = false)

            val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

            assertEquals(512, frame.width)
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/live", request.path)
            assertTrue("le corps porte l'image", "image_b64" in request.body && "QQ==" in request.body)
        }
    }

    @Test
    fun `le repli POST de la fabrique porte les knobs du WS`() = runBlocking {
        NetContract.server(body = NetContract.frameReply(id = null)).use { server ->
            val client = liveClientWith(
                server.baseUrl, 1L, maskFormat = "rle", maskEdge = 256, withDepth = true,
            ) { CountingRefusedTransport() }

            client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

            val body = JSONObject(server.requests.single().body)
            assertEquals("rle", body.getString("mask_format"))
            assertEquals(256, body.getInt("mask_edge"))
            assertTrue("profondeur demandée comme sur le WS", body.getBoolean("with_depth"))
        }
    }
}
