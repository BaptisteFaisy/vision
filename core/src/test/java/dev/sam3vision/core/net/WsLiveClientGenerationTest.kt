package dev.sam3vision.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun `une fermeture idle 1011 dit son motif`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(WsClosedAbnormally(1011, "idle"))
            emptyList()
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue("motif 1011 attendu, obtenu ${failure?.message}", "1011" in (failure?.message ?: ""))
    }
}
