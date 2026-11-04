package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bascule réseau (PR-E) : deux réseaux Wi-Fi successifs sont deux routes
 * ([netIfaceKey]), et jeter le socket purge les pools HTTP — sinon le POST de
 * repli et la re-sonde réutilisent des connexions keep-alive branchées sur
 * l'interface morte. Aucun réseau : [SwitchTransport] et [FakeNetworkState].
 */
class WsLiveClientNetSwitchTest {

    private val base = "https://sam.example"

    /** Faux transport qui compte ouvertures et fermetures, et répond à chaque frame. */
    private class SwitchTransport : WsTransport {
        var opens = 0
            private set
        var closes = 0
            private set
        private var reader: ((String) -> Unit)? = null

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            reader = onText
            onOpen()
            return true
        }

        override fun send(text: String): Boolean {
            if ("\"cancel\"" !in text) reader?.invoke(NetContract.frameReply(JSONObject(text).getInt("id")))
            return true
        }

        override fun sendBytes(bytes: ByteArray): Boolean = true

        override fun close() {
            closes += 1
        }
    }

    private fun client(
        transport: SwitchTransport,
        net: NetworkState,
        onDiscarded: () -> Unit = {},
    ) = WsLiveClient(
        base, transport, 1_000, failingHttpClient(), binaryWs = false, keepAlive = null,
        net = net, link = WsLink(), httpFallback = WsHttpFallback(probeEvery = 1),
        onSocketDiscarded = onDiscarded,
    )

    @Test
    fun `netIfaceKey distingue deux reseaux wifi par leur netId`() {
        assertEquals("wifi#109", netIfaceKey("wifi", "109"))
        assertTrue(netIfaceKey("wifi", "109") != netIfaceKey("wifi", "110"))
    }

    @Test
    fun `netIfaceKey sans netId garde le transport seul`() {
        assertEquals("cell", netIfaceKey("cell", null))
        assertEquals("wifi", netIfaceKey("wifi", null))
    }

    @Test
    fun `un changement de reseau meme transport jette le socket`() = runBlocking {
        val transport = SwitchTransport()
        val net = FakeNetworkState(iface = "wifi#109")
        val client = client(transport, net)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals(1, transport.opens)

        net.iface = "wifi#110"
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("socket de l'ancien réseau fermé", 1, transport.closes)
        assertEquals("une seule réouverture, sur le nouveau réseau", 2, transport.opens)
        assertEquals(WsLinkState.Open, client.linkState.value)
        client.close()
    }

    @Test
    fun `jeter le socket pour reseau change purge les pools HTTP`() = runBlocking {
        val transport = SwitchTransport()
        val net = FakeNetworkState(iface = "wifi#109")
        var discards = 0
        val client = client(transport, net, onDiscarded = { discards += 1 })

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        net.iface = "cell#111"
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("une purge par socket jeté", 1, discards)
        client.close()
    }

    @Test
    fun `le passage hors ligne purge les pools HTTP`() = runBlocking {
        val transport = SwitchTransport()
        val net = FakeNetworkState(iface = "wifi#109")
        var discards = 0
        val client = client(transport, net, onDiscarded = { discards += 1 })

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        net.available = false
        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("pools purgés dès la perte, la reprise repart propre", 1, discards)
        client.close()
    }

    @Test
    fun `sans changement de reseau aucune purge ni reouverture`() = runBlocking {
        val transport = SwitchTransport()
        val net = FakeNetworkState(iface = "wifi#109")
        var discards = 0
        val client = client(transport, net, onDiscarded = { discards += 1 })

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(1, transport.opens)
        assertEquals(0, transport.closes)
        assertEquals("pas de purge sans bascule", 0, discards)
        client.close()
    }
}
