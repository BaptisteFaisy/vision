package dev.sam3vision.core.net

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cycle de vie d'une session Muse realtime, **sans réseau** : poignée et
 * clé, tampon avant l'ack, trames binaires 80 ms, `endStream` au
 * relâchement, morts (erreur trame, coupures 1008/1011/1013, délais) et
 * repli. Les trames et le parsing seuls vivent dans
 * [MetaRealtimeClientTest].
 */
class MetaRealtimeContractTest {

    private val base = "https://api.meta.ai/v1"

    private fun session(
        transport: ContractWsTransport,
        partials: MutableList<String> = mutableListOf(),
        deaths: MutableList<ApiError> = mutableListOf(),
        startTimeoutMs: Long = 2_000L,
        finalTimeoutMs: Long = 2_000L,
    ) = MetaRealtimeSession(
        baseUrl = base,
        apiKey = NetContract.KEY,
        transport = transport,
        startTimeoutMs = startTimeoutMs,
        finalTimeoutMs = finalTimeoutMs,
        onPartial = partials::add,
        onError = deaths::add,
    )

    /** `start()` est lancé en tâche : attendre que le handshake soit parti. */
    private suspend fun awaitOpen(transport: ContractWsTransport) {
        while (transport.openedUrl == null) yield()
    }

    @Test
    fun `start envoie la poignée sans en-tête et attend l'ack`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport)
        val starting = async { session.start() }
        awaitOpen(transport)

        assertEquals("wss://api.meta.ai/v1/asr/realtime", transport.openedUrl)
        assertTrue("pas de clé en en-tête (ignorée par Meta)", transport.openedHeaders.isEmpty())
        // La poignée part avec l'ouverture (coupure 1008 passé 10 s).
        while (transport.sent.isEmpty()) yield()
        val handshake = JSONObject(transport.sent.single())
        assertEquals("PUSH_TO_TALK", handshake.getString("mode"))
        assertEquals(NetContract.KEY, handshake.getJSONObject("authorization").getString("accessToken"))
        transport.deliver("""{"sessionId":"abc"}""")

        starting.await().getOrThrow()
        assertTrue(session.isLive)
    }

    @Test
    fun `clé absente refuse sans réseau`() = runBlocking {
        val transport = ContractWsTransport()
        val session = MetaRealtimeSession(base, apiKey = "", transport = transport)

        NetContract.assertError(session.start(), ApiError.InvalidKey::class.java, "clé absente")
        assertEquals(null, transport.openedUrl)
    }

    @Test
    fun `base absente refuse sans réseau`() = runBlocking {
        val transport = ContractWsTransport()
        val session = MetaRealtimeSession("", NetContract.KEY, transport = transport)

        NetContract.assertError(session.start(), ApiError.Network::class.java, "base absente")
        assertEquals(null, transport.openedUrl)
    }

    @Test
    fun `ouverture ou poignée refusée donne Network`() = runBlocking {
        val refused = ContractWsTransport().apply { openResult = false }
        NetContract.assertError(session(refused).start(), ApiError.Network::class.java, "ouverture refusée")

        val mute = ContractWsTransport().apply { sendResult = false }
        NetContract.assertError(session(mute).start(), ApiError.Network::class.java, "poignée refusée")
    }

    @Test
    fun `silence du serveur donne Timeout`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport, startTimeoutMs = 60L)

        NetContract.assertError(session.start(), ApiError.Timeout::class.java, "session non démarrée")
        assertFalse(session.isLive)
    }

    @Test
    fun `le PCM avant l'ack est tamponné puis flushé en trames de 80 ms`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport)
        val starting = async { session.start() }
        awaitOpen(transport)

        assertTrue(session.sendPcm(ByteArray(6_000) { (it % 251).toByte() }))
        assertTrue("rien ne part avant l'ack", transport.sentBytes.isEmpty())
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        assertTrue(session.isLive)
        assertEquals("5120 en 2 trames, 880 en reliquat", 2, transport.sentBytes.size)
        transport.sentBytes.forEach { assertEquals(2_560, it.size) }
        transport.answerWith { json ->
            if (json.optString("type") == "endStream") finalOf("ok") else emptyList()
        }
        assertEquals("ok", session.endStreamAndFinal().getOrThrow())
        assertEquals(880, transport.sentBytes.last().size)
        assertEquals(MetaRealtimeSession.END_STREAM, transport.sent.last())
        assertEquals("poignée + endStream", 2, transport.sent.size)
    }

    @Test
    fun `un multiple exact de trame finit par endStream seul`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport)
        val starting = async { session.start() }
        awaitOpen(transport)
        session.sendPcm(ByteArray(5_120))
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        assertEquals(2, transport.sentBytes.size)
        transport.answerWith { json ->
            if (json.optString("type") == "endStream") finalOf("ok") else emptyList()
        }
        session.endStreamAndFinal().getOrThrow()
        assertEquals("aucune trame binaire vide", 2, transport.sentBytes.size)
        assertTrue(transport.closed)
        assertFalse(session.isLive)
    }

    @Test
    fun `les partiels cumulatifs sont transmis et le final horodaté accepté`() = runBlocking {
        val transport = ContractWsTransport()
        val partials = mutableListOf<String>()
        val session = session(transport, partials = partials)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        transport.deliver("""{"type":"transcript","transcript":"where is","final":false}""")
        transport.deliver("""{"type":"transcript","transcript":"where is the","final":false}""")
        assertEquals(listOf("where is", "where is the"), partials)

        session.sendPcm(ByteArray(100))
        transport.answerWith {
            listOf("""{"type":"speechComplete","turnId":0,"speaker":"A","text":"done","transcript":"done"}""")
        }
        assertEquals("done", session.endStreamAndFinal().getOrThrow())
    }

    @Test
    fun `un refus de poignée échoue le start avec sa cause`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)

        // État réel du compte : la poignée est refusée pour facturation.
        transport.deliver("""{"type":"error","message":"billing_not_configured"}""")
        NetContract.assertError(starting.await(), ApiError.PaymentRequired::class.java, "poignée refusée")
        assertTrue("session jamais née : pas de signal de mort", deaths.isEmpty())
        assertFalse(session.sendPcm(ByteArray(100)))
    }

    @Test
    fun `une erreur en pleine tenue signale une seule fois puis la fin échoue`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        transport.deliver("""{"type":"error","message":"kaput"}""")
        transport.disconnect(java.io.IOException("coupe"))
        assertEquals("erreur trame + fermeture = un seul signal", 1, deaths.size)
        assertTrue(deaths.single() is ApiError.BadResponse)
        assertFalse(session.isLive)
        NetContract.assertError(session.endStreamAndFinal(), ApiError.Network::class.java, "fin après mort")
    }

    @Test
    fun `une coupure quota 1013 devient RateLimited`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        transport.disconnect(WsClosedAbnormally(1013, "rate limited"))

        assertEquals(1, deaths.size)
        assertTrue(deaths.single() is ApiError.RateLimited)
    }

    @Test
    fun `une fermeture propre après final ne signale pas d'erreur`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()
        session.sendPcm(ByteArray(100))
        transport.answerWith { finalOf("ok") }

        session.endStreamAndFinal().getOrThrow()
        transport.disconnect(null)

        assertTrue(deaths.isEmpty())
    }

    @Test
    fun `une fin de tour sans audio donne audio vide sans réseau`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        val result = session.endStreamAndFinal()
        NetContract.assertError(result, ApiError.BadResponse::class.java, "audio vide")
        assertEquals("seule la poignée est partie", 1, transport.sent.size)
        assertTrue(transport.sentBytes.isEmpty())
    }

    @Test
    fun `une fin de tour sans réponse donne Timeout`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport, finalTimeoutMs = 60L)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()
        session.sendPcm(ByteArray(100))

        NetContract.assertError(session.endStreamAndFinal(), ApiError.Timeout::class.java, "tour muet")
    }

    @Test
    fun `abort ferme sans endStream ni signal`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"sessionId":"abc"}""")
        starting.await().getOrThrow()

        session.abort()

        assertTrue(transport.closed)
        assertEquals("seule la poignée est partie", 1, transport.sent.size)
        assertTrue(deaths.isEmpty())
        assertFalse(session.isLive)
    }

    @Test
    fun `un tampon plein avant l'ack refuse l'audio`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport)
        val starting = async { session.start() }
        awaitOpen(transport)

        assertFalse(session.sendPcm(ByteArray(MetaRealtimeSession.PENDING_CAP_BYTES + 1)))
        transport.deliver("""{"sessionId":"abc"}""")
        NetContract.assertError(starting.await(), ApiError.BadResponse::class.java, "tampon plein")
    }

    private fun finalOf(text: String): List<String> =
        listOf("""{"type":"transcript","transcript":"$text","final":true}""")
}
