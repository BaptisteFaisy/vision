package dev.sam3vision.core.net

import kotlinx.coroutines.async
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
 * Perte de connexion = **état**, pas une frame qui expire : hors ligne refusé localement
 * (aucun handshake), interface changée prise en compte, silence qui tue le socket et
 * réveille les frames en vol, reprises espacées par le backoff, retour à `Open` sur succès.
 * Aucun réseau, aucune horloge réelle : [LinkTransport] et [WsLink] à horloge injectée.
 */
class WsLiveClientResilienceTest {

    private val base = "https://sam.example"

    /** Faux transport multi-sockets : compte tentatives et fermetures, sait se taire. */
    private class LinkTransport : WsTransport {
        /** Tentatives d'ouverture (refus compris) : la mesure « pas de tempête ». */
        var opens = 0
            private set
        var closes = 0
            private set
        var openResult = true
        var sendResult = true

        /** `true` = le serveur répond à chaque envoi (socket chaud) ; `false` = silence. */
        var answering = false
        val sent = mutableListOf<String>()
        val sentBytes = mutableListOf<ByteArray>()
        private val readers = mutableListOf<(String) -> Unit>()

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            if (!openResult) return false
            readers += onText
            onOpen()
            return true
        }

        override fun send(text: String): Boolean {
            if (!sendResult) return false
            sent += text
            if (answering && "\"cancel\"" !in text) {
                readers.last()(NetContract.frameReply(JSONObject(text).getInt("id")))
            }
            return true
        }

        override fun sendBytes(bytes: ByteArray): Boolean {
            sentBytes += bytes
            return true
        }

        override fun close() {
            closes += 1
        }
    }

    private fun client(
        transport: LinkTransport,
        net: NetworkState = NetworkState.Always,
        link: WsLink = WsLink(),
        timeoutMs: Long = 1_000L,
    ) = WsLiveClient(base, transport, timeoutMs, RecordingLiveClient(), binaryWs = false, keepAlive = null, net = net, link = link)

    @Test
    fun `hors ligne connu n ouvre aucun socket`() = runBlocking {
        val transport = LinkTransport()
        val client = client(transport, net = FakeNetworkState(available = false))

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("aucun handshake hors ligne", 0, transport.opens)
        assertTrue("aucune frame n'a quitté le téléphone", transport.sent.isEmpty())
        assertFalse(client.isConnected)
        assertEquals("l'écran peut dire « reconnexion »", WsLinkState.Degraded, client.linkState.value)
    }

    @Test
    fun `hors ligne preconnect ne tente rien et le retour repart tout de suite`() = runBlocking {
        val transport = LinkTransport().apply { answering = true }
        val net = FakeNetworkState(available = false)
        val client = client(transport, net = net)

        assertFalse("pas de socket hors ligne", client.preconnect())
        assertEquals(0, transport.opens)

        net.available = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(1, transport.opens)
        assertEquals(WsLinkState.Open, client.linkState.value)
    }

    @Test
    fun `un changement d interface jette le socket de l ancienne`() = runBlocking {
        val transport = LinkTransport().apply { answering = true }
        val net = FakeNetworkState(iface = "wifi")
        val client = client(transport, net = net)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals(1, transport.opens)

        net.iface = "cell"
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("socket de l'ancienne interface fermé", 1, transport.closes)
        assertEquals("une seule réouverture, sur la bonne interface", 2, transport.opens)
        assertEquals(WsLinkState.Open, client.linkState.value)
    }

    @Test
    fun `le silence tue le socket et reveille les frames en vol`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = LinkTransport()
        val client = client(transport, link = link, timeoutMs = 120_000)

        val frame = async { client.live("QQ==", listOf("person"), 640, 4) }
        withTimeout(2_000) { while (transport.sent.isEmpty()) delay(5) }
        assertTrue(client.isConnected)

        now += link.silenceBudgetMs(120_000) + 1
        client.checkSilence()

        val failure = frame.await().exceptionOrNull()
        assertTrue("frame en vol réveillée en échec, obtenu $failure", failure is ApiError.Network)
        assertEquals("socket déclaré mort", 1, transport.closes)
        assertFalse(client.isConnected)
        assertEquals(WsLinkState.Degraded, client.linkState.value)

        // Le direct se rouvre seul : la frame suivante trouve un socket neuf, sans backoff.
        transport.answering = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals(2, transport.opens)
        assertEquals(WsLinkState.Open, client.linkState.value)
    }

    @Test
    fun `un socket repondant n est jamais declare muet`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = LinkTransport().apply { answering = true }
        val client = client(transport, link = link, timeoutMs = 120_000)

        now += 110_000
        client.checkSilence()
        assertEquals("aucune preuve de vie encore : rien à tuer sans frame", 0, transport.closes)

        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        now += 110_000
        client.checkSilence()

        assertTrue("la réponse a reculé le watchdog", client.isConnected)
        assertEquals(0, transport.closes)
    }

    @Test
    fun `le backoff empeche les rafales de handshakes`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = LinkTransport().apply { openResult = false }
        val client = client(transport, link = link)

        assertTrue(client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull() is ApiError.Network)
        assertEquals("première reprise immédiate", 1, transport.opens)
        assertTrue(client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull() is ApiError.Network)
        assertEquals(2, transport.opens)
        assertEquals(2, link.attemptCount)

        assertTrue(client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull() is ApiError.Network)
        assertEquals("aucune ouverture pendant la fenêtre de backoff", 2, transport.opens)

        // 2 s : au-delà du plafond du second palier (base 500 ms ± 25 % de jitter),
        // sinon le test dépendrait d'un tirage aléatoire.
        now += 2_000
        transport.openResult = true
        transport.answering = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("reprise après la fenêtre", 3, transport.opens)
        assertEquals("succès ⇒ compteur à zéro", 0, link.attemptCount)
        assertEquals(WsLinkState.Open, client.linkState.value)
    }

    @Test
    fun `un envoi refuse invalide le socket au lieu de le croire vivant`() = runBlocking {
        val transport = LinkTransport().apply { sendResult = false }
        val client = client(transport)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("socket jeté", 1, transport.closes)
        assertFalse(client.isConnected)
        assertEquals(WsLinkState.Degraded, client.linkState.value)
    }

    @Test
    fun `close remet l etat du lien a zero`() {
        var now = 0L
        val link = WsLink(clock = { now })
        val client = client(LinkTransport().apply { answering = true }, link = link)

        client.preconnect()
        assertEquals(WsLinkState.Open, client.linkState.value)

        now += 10_000
        link.onFailure()
        link.onFailure()
        client.close()

        assertEquals(WsLinkState.Closed, client.linkState.value)
        assertEquals(0, link.attemptCount)
        assertNull("plus d'interface retenue", link.snapshot().iface)
        assertFalse(client.isConnected)
    }

    @Test
    fun `la fabrique transmet le reseau et le budget`() = runBlocking {
        val transport = LinkTransport()
        val client = liveClientWith(
            baseUrl = base,
            timeoutSeconds = 10L,
            net = FakeNetworkState(available = false),
            budget = LinkBudget(baseDelayMs = 100, maxDelayMs = 200),
            transportFor = { transport },
        )

        val failure = client.live("QQ==", null, 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("le réseau injecté est bien celui du client", 0, transport.opens)
        client.close()
    }
}
