package dev.sam3vision.core.net

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Handshake asynchrone : `open()` rend `true` avant le `101` (OkHttp), les échecs
 * arrivent plus tard par `onClosed`. La reprise n'aboutit qu'au `101` : un `true`
 * optimiste ne remet ni le compteur ni le backoff (sinon chaque échec retombait à
 * la première marche — reprise immédiate en boucle, « no reply for 60 s »), et un
 * `101` tardif ne ressuscite rien. Horloge injectée : aucune attente réelle.
 */
class WsLiveClientHandshakeTest {

    private val base = "https://sam.example"

    /** Faux réseau : `open` optimiste, `101` manuel ([accept]) et échec async ([fail]). */
    private class HandshakeTransport : WsTransport {
        var opens = 0
        var closes = 0

        /** `101` synchrone (faux chaud) ou différé (faux réseau : `accept` manuel). */
        var auto101 = false
        private val onOpens = mutableListOf<() -> Unit>()
        private val onCloseds = mutableListOf<(Throwable?) -> Unit>()

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            onOpens += onOpen
            onCloseds += onClosed
            if (auto101) onOpen()
            return true
        }

        override fun send(text: String): Boolean = true

        override fun sendBytes(bytes: ByteArray): Boolean = true

        override fun close() {
            closes += 1
        }

        /** Le `101` du socket n° [index] arrive (tardif = périmé, ignoré). */
        fun accept(index: Int) = onOpens[index]()

        /** Le handshake n° [index] échoue en async, sans `101` (radio coupée). */
        fun fail(index: Int, error: Throwable = IOException("radio coupée")) = onCloseds[index](error)
    }

    private fun clientFor(transport: HandshakeTransport, link: WsLink, timeoutMs: Long = 60_000L) =
        WsLiveClient(base, transport, timeoutMs, RecordingLiveClient(), binaryWs = false, keepAlive = null, link = link)

    private suspend fun awaitOpens(transport: HandshakeTransport, n: Int) {
        withTimeout(2_000) { while (transport.opens < n) delay(5) }
    }

    @Test
    fun `des handshakes qui echouent en async accumulent le backoff au lieu de mitrailler`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now }, random = { 0.5 })
        val transport = HandshakeTransport()
        val client = clientFor(transport, link)

        val first = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 1)
        transport.fail(0)
        assertTrue(first.await().exceptionOrNull() is ApiError.Network)
        assertEquals(1, link.attemptCount)
        assertTrue("1re reprise immédiate", link.canAttempt())

        val second = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 2)
        transport.fail(1)
        assertTrue(second.await().exceptionOrNull() is ApiError.Network)
        assertEquals("le `true` optimiste ne remet plus le compteur", 2, link.attemptCount)
        assertFalse("fenêtre de 500 ms", link.canAttempt())

        val refused = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()
        assertTrue(refused is ApiError.Network)
        assertEquals("aucun dial pendant la fenêtre", 2, transport.opens)

        now += 500
        val third = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 3)
        transport.fail(2)
        assertTrue(third.await().exceptionOrNull() is ApiError.Network)
        assertEquals(3, link.attemptCount)
        assertEquals("jamais `Open` sans `101`", WsLinkState.Degraded, client.linkState.value)
        client.close()
    }

    @Test
    fun `un 101 tardif ne remet pas le compteur a zero`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = HandshakeTransport()
        val client = clientFor(transport, link)

        val first = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 1)
        transport.fail(0)
        assertTrue(first.await().exceptionOrNull() is ApiError.Network)
        assertEquals(1, link.attemptCount)

        val second = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 2)
        assertEquals(WsLinkState.Connecting, client.linkState.value)

        transport.accept(0)
        assertEquals("`101` périmé ignoré", 1, link.attemptCount)
        assertEquals(WsLinkState.Connecting, client.linkState.value)

        transport.accept(1)
        assertEquals("`101` courant : reprise aboutie", 0, link.attemptCount)
        assertEquals(WsLinkState.Open, client.linkState.value)
        client.close()
        assertTrue(second.await().exceptionOrNull() is ApiError.Network)
    }

    @Test
    fun `un socket neuf a son budget de silence entier`() = runBlocking {
        var now = 0L
        val link = WsLink(clock = { now })
        val transport = HandshakeTransport()
        val client = clientFor(transport, link, timeoutMs = 10_000)
        val budget = link.silenceBudgetMs(10_000)

        val first = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 1)
        now += budget + 1
        client.checkSilence()
        assertTrue(first.await().exceptionOrNull() is ApiError.Network)
        assertEquals(1, transport.closes)

        val second = async { client.live("QQ==", listOf("person"), 640, 4) }
        awaitOpens(transport, 2)
        now += budget - 1
        client.checkSilence()
        assertEquals("le dial réarme le watchdog", 1, transport.closes)
        assertTrue(client.isConnected)

        now += 2
        client.checkSilence()
        assertTrue(second.await().exceptionOrNull() is ApiError.Network)
        assertEquals(2, transport.closes)
        client.close()
    }
}
