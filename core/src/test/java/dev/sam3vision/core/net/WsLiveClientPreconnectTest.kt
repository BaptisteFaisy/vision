package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handshake anticipé : [WsLiveClient.preconnect] ouvre le socket sans envoyer, pour que la
 * première frame parte sans attendre. Testé sans réseau, en comptant les ouvertures.
 */
class WsLiveClientPreconnectTest {

    private val base = "https://sam.example"

    /** Compte les ouvertures, délègue le reste au harnais (réponses synchrones). */
    private class CountingOpenTransport(val inner: ContractWsTransport) : WsTransport by inner {
        var opens = 0

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            return inner.open(url, onText, onClosed, onOpen, headers)
        }
    }

    private fun clientFor(inner: ContractWsTransport): Pair<WsLiveClient, CountingOpenTransport> {
        val transport = CountingOpenTransport(inner)
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)
        return client to transport
    }

    @Test
    fun `preconnect ouvre le socket sans rien envoyer`() {
        val inner = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val (client, transport) = clientFor(inner)

        assertTrue(client.preconnect())

        assertEquals(1, transport.opens)
        assertEquals("wss://sam.example/live/ws", inner.openedUrl)
        assertTrue("rien n'est envoyé", inner.sent.isEmpty())
        assertTrue(client.isConnected)
    }

    @Test
    fun `preconnect est idempotent`() {
        val inner = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val (client, transport) = clientFor(inner)

        assertTrue(client.preconnect())
        assertTrue(client.preconnect())

        assertEquals("une seule ouverture", 1, transport.opens)
    }

    @Test
    fun `la premiere frame reutilise le socket preconnecte`() = runBlocking {
        val inner = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val (client, transport) = clientFor(inner)
        client.preconnect()

        client.live("SUVLFw==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("pas de seconde ouverture", 1, transport.opens)
        assertEquals(1, inner.sent.size)
    }

    @Test
    fun `preconnect refuse puis la frame suivante retente l'ouverture`() = runBlocking {
        val inner = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val (client, transport) = clientFor(inner)
        inner.openResult = false

        assertFalse(client.preconnect())
        assertFalse(client.isConnected)

        inner.openResult = true
        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals("refus + nouvelle tentative", 2, transport.opens)
    }

    @Test
    fun `sans preconnect la premiere frame ouvre comme avant`() = runBlocking {
        val inner = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val (client, transport) = clientFor(inner)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(1, transport.opens)
        assertEquals(1, inner.sent.size)
    }
}
