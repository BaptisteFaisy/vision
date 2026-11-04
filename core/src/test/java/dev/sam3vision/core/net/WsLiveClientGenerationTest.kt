package dev.sam3vision.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Générations de socket : un délai dépassé ou une fermeture périmés (le socket a
 * été rouvert depuis) ne tuent pas le socket neuf. Sans ça, à deux créneaux, le
 * timeout d'une frame fermait le socket rouvert par sa sœur — coupure fantôme.
 */
class WsLiveClientGenerationTest {

    private val base = "https://sam.example"

    /** Faux transport multi-sockets : chaque `open` garde ses rappels, `close` comptés. */
    private class GenerationTransport : WsTransport {
        var opens = 0
            private set
        var closes = 0
            private set

        /** `true` = le serveur répond à chaque envoi (comme un socket chaud). */
        var answering = false
        private val onTexts = mutableListOf<(String) -> Unit>()
        private val onCloseds = mutableListOf<(Throwable?) -> Unit>()

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
            headers: Map<String, String>,
        ): Boolean {
            opens += 1
            onTexts += onText
            onCloseds += onClosed
            return true
        }

        override fun send(text: String): Boolean {
            if (answering) {
                val id = JSONObject(text).getInt("id")
                onTexts.last()(NetContract.frameReply(id))
            }
            return true
        }

        override fun sendBytes(bytes: ByteArray): Boolean = true

        override fun close() {
            closes += 1
        }

        /** Le socket n° [index] se ferme comme le ferait le serveur (ack tardif, idle). */
        fun fireClose(index: Int, error: Throwable?) = onCloseds[index](error)
    }

    @Test
    fun `une fermeture perimee ne tue pas le socket neuf`() = runBlocking {
        val transport = GenerationTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 100, http = RecordingLiveClient(), binaryWs = false)

        val first = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()
        assertTrue("attendu Timeout, obtenu $first", first is ApiError.Timeout)
        assertEquals(1, transport.closes)

        transport.answering = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals(2, transport.opens)

        transport.fireClose(0, null)

        assertTrue("le socket neuf survit à l'ack tardif", client.isConnected)
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals("pas de réouverture", 2, transport.opens)
    }

    @Test
    fun `le timeout d une vieille frame ne ferme pas le socket rouvert`() = runBlocking {
        val transport = GenerationTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 300, http = RecordingLiveClient(), binaryWs = false)

        val first = async { client.live("QQ==", listOf("person"), 640, 4) }
        delay(250)
        val sibling = async { client.live("QQ==", listOf("person"), 640, 4) }
        val firstError = first.await().exceptionOrNull()
        assertTrue("attendu Timeout, obtenu $firstError", firstError is ApiError.Timeout)
        assertEquals(1, transport.closes)

        transport.answering = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        assertEquals(2, transport.opens)

        val siblingError = sibling.await().exceptionOrNull()
        assertTrue("attendu Timeout, obtenu $siblingError", siblingError is ApiError.Timeout)
        assertEquals("le socket neuf survit", 1, transport.closes)
        assertTrue(client.isConnected)
    }

    @Test
    fun `une fermeture idle 1001 dit son motif, compte sans delai`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(WsClosedAbnormally(1001, "idle"))
            emptyList()
        }
        val link = WsLink()
        val http = failingHttpClient(ApiError.Network("POST /live : serveur éteint"))
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false, link = link)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("le repli POST a été tenté, son erreur remonte", "POST /live : serveur éteint", failure?.message)
        assertEquals("motif 1001 gardé", 1001, client.linkSnapshot.lastCloseCode)
        // Un idle qui ne comptait pas, c'était la boucle : chaque réouverture
        // repartait immédiate, sans jamais armer le backoff ni le palier muet.
        assertEquals("l'idle compte, mais sans délai", 1, link.attemptCount)
        assertTrue("premier idle : reprise immédiate", link.canAttempt())
        assertEquals(WsLinkState.Degraded, client.linkState.value)
    }

    @Test
    fun `des idle 1001 repetes sans preuve arment le backoff`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(WsClosedAbnormally(1001, "idle"))
            emptyList()
        }
        val link = WsLink()
        val http = failingHttpClient(ApiError.Network("POST /live : serveur éteint"))
        val client = WsLiveClient(
            base, transport, 1_000, http, binaryWs = false, link = link,
            httpFallback = WsHttpFallback(probeEvery = 1),
        )

        client.live("QQ==", listOf("person"), 640, 4)
        client.live("QQ==", listOf("person"), 640, 4)
        assertEquals(2, link.attemptCount)
        assertFalse("2ᵉ idle consécutif : backoff armé", link.canAttempt())
        assertEquals("un envoi par tentative WS", 2, transport?.sent?.size)

        val refused = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()
        assertTrue("attendu Network, obtenu $refused", refused is ApiError.Network)
        assertTrue(refused?.message.orEmpty().contains("reconnexion"))
        assertEquals("pas de 3ᵉ handshake dans la fenêtre", 2, transport?.sent?.size)
        client.close()
    }

    @Test
    fun `un timeout sur un socket qui sert epargne le socket et ses soeurs`() = runBlocking {
        val transport = GenerationTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 500, http = RecordingLiveClient(), binaryWs = false)

        val slow = async { client.live("QQ==", listOf("person"), 640, 4) }
        delay(350)
        // Une sœur répondue pendant l'attente : le socket sert, le timeout est
        // une lenteur, pas une mort — le déco/reco + le massacre des sœurs
        // à chaque frame lente, c'était la boucle.
        transport.answering = true
        client.live("QQ==", listOf("person"), 640, 4).getOrThrow()
        transport.answering = false

        val slowError = slow.await().exceptionOrNull()
        assertTrue("attendu Timeout, obtenu $slowError", slowError is ApiError.Timeout)
        assertEquals("pas de réouverture : le socket sert", 1, transport.opens)
        assertEquals("socket épargné", 0, transport.closes)
        assertTrue(client.isConnected)
        client.close()
    }

    @Test
    fun `une fermeture 1011 dit erreur interne et consomme un backoff`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(WsClosedAbnormally(1011, "internal"))
            emptyList()
        }
        val link = WsLink(clock = { 0L })
        val http = failingHttpClient(ApiError.Network("POST /live : serveur éteint"))
        val client = WsLiveClient(base, transport, 1_000, http, binaryWs = false, link = link)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertEquals("le repli POST a été tenté, son erreur remonte", "POST /live : serveur éteint", failure?.message)
        assertEquals("motif 1011 gardé", 1011, client.linkSnapshot.lastCloseCode)
        assertEquals("un 1011 consomme une tentative", 1, link.attemptCount)
    }
}
