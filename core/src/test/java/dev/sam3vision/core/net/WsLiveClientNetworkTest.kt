package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tranche RÉSEAU (FIX-16/18, FIX-4/8, FIX-17) : budgets, reprise immédiate, cancel
 * one-way, ping applicatif, timeout décroissant et jambe sans sonde — sans réseau
 * ([ContractWsTransport]) ni GPU.
 */
class WsLiveClientNetworkTest {

    private val base = "https://sam.example"

    @Test
    fun `les budgets steady valent 5 s, froid et reveil inchanges`() {
        assertEquals(5L, LIVE_TIMEOUT_SECONDS)
        assertEquals(180L, WAKE_TIMEOUT_SECONDS)
    }

    @Test
    fun `useTimeoutMs fait retomber le budget sans ouvrir de socket`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 30_000, http = RecordingLiveClient(), binaryWs = false)

        client.useTimeoutMs(80)

        assertEquals(80, client.currentTimeoutMs)
        assertNull("aucun socket ouvert", transport.openedUrl)
        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()
        assertTrue("budget de 80 ms honoré, obtenu $failure", failure is ApiError.Timeout)
        client.close()
    }

    @Test
    fun `le bit ws_binary faux fait partir en JSON sans sonde`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.useBinaryWs(false)
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertTrue("aucune trame M1", transport.sentBytes.isEmpty())
        assertEquals(1, transport.sent.size)
        assertEquals("QQ==", JSONObject(transport.sent.single()).getString("image_b64"))
        client.close()
    }

    @Test
    fun `health lit le bit ws_binary quand le serveur l expose`() {
        TestHttpServer { TestHttpServer.Response(200, """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100","ws_binary":false}""") }.use { server ->
            val health = runBlocking { HttpLiveClient(server.baseUrl).health().getOrThrow() }
            assertFalse(health.wsBinary)
        }
    }

    @Test
    fun `health sans bit ws_binary garde la jambe binaire avec sonde`() {
        TestHttpServer { TestHttpServer.Response(200, """{"ok":true,"model":"sam3.1","device":"cuda","gpu":"A100"}""") }.use { server ->
            val health = runBlocking { HttpLiveClient(server.baseUrl).health().getOrThrow() }
            assertTrue(health.wsBinary)
        }
    }

    @Test
    fun `le cancel one-way porte l id de la frame abandonnee`() {
        assertEquals("""{"id":7,"cancel":true}""", cancelMessage(7))
    }

    @Test
    fun `le timeout envoie le cancel de la frame avant de rouvrir`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 100, http = RecordingLiveClient(), binaryWs = false)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        assertEquals(listOf("frame", "cancel"), transport.sent.map { if ("\"cancel\"" in it) "cancel" else "frame" })
        val cancel = JSONObject(transport.sent.last())
        assertEquals(1, cancel.getInt("id"))
        assertTrue(cancel.getBoolean("cancel"))
        client.close()
    }

    @Test
    fun `le ping applicatif porte type ping et un id negatif`() {
        assertEquals("""{"type":"ping","id":-2}""", pingMessage(-2))
    }

    @Test
    fun `pas de ping avant 30 s sans frame en vol`() {
        var now = 1_000_000L
        val pump = WsKeepAlive(clock = { now })

        now += PING_INTERVAL_MS - 1
        assertFalse(pump.poll(inFlight = false))
        now += 1
        assertTrue(pump.poll(inFlight = false))
        assertFalse("un seul ping par tick", pump.poll(inFlight = false))
    }

    @Test
    fun `pas de ping quand une frame est en vol`() {
        var now = 0L
        val pump = WsKeepAlive(clock = { now })

        now += 120_000L
        assertFalse(pump.poll(inFlight = true))
        assertTrue(pump.poll(inFlight = false))
    }

    @Test
    fun `une frame re-arme le delai de 30 s`() {
        var now = 0L
        val pump = WsKeepAlive(clock = { now })

        now += 20_000L
        pump.markActive()
        now += 20_000L
        assertFalse(pump.poll(inFlight = false))
        now += 10_000L
        assertTrue(pump.poll(inFlight = false))
    }

    @Test
    fun `le client pingue en idle et la pompe s arrete a close`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, 30_000, RecordingLiveClient(), binaryWs = false, keepAlive = WsKeepAlive(intervalMs = 60))

        assertTrue(client.preconnect())
        assertTrue(client.pingActive)
        withTimeout(2_000) { while (transport.sent.none { "\"type\":\"ping\"" in it }) delay(10) }
        val ping = JSONObject(transport.sent.first { "\"type\":\"ping\"" in it })
        assertEquals("ping", ping.getString("type"))
        assertTrue("id négatif, hors espace des frames", ping.getInt("id") < -1)

        client.close()
        assertFalse(client.pingActive)
    }
}
