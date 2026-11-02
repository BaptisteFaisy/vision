package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import kotlinx.coroutines.runBlocking
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
}
