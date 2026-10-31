package dev.sam3vision.core.net

import java.util.Base64
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cycle de vie d'une session Scribe realtime, **sans réseau** : handshake et
 * clé, tampon avant `session_started`, tramage 100 ms avec rétention de la
 * dernière trame, commit au relâchement, morts (erreur trame, fermeture,
 * délais) et repli. Les trames et le parsing seuls vivent dans
 * [ScribeRealtimeClientTest].
 */
class ScribeRealtimeContractTest {

    private fun session(
        transport: ContractWsTransport,
        partials: MutableList<String> = mutableListOf(),
        deaths: MutableList<ApiError> = mutableListOf(),
        startTimeoutMs: Long = 2_000L,
        commitTimeoutMs: Long = 2_000L,
    ) = ScribeRealtimeSession(
        apiKey = NetContract.KEY,
        transport = transport,
        startTimeoutMs = startTimeoutMs,
        commitTimeoutMs = commitTimeoutMs,
        onPartial = partials::add,
        onError = deaths::add,
    )

    /** `start()` est lancé en tâche : attendre que le handshake soit parti. */
    private suspend fun awaitOpen(transport: ContractWsTransport) {
        while (transport.openedUrl == null) yield()
    }

    private fun startLive(transport: ContractWsTransport) = ScribeRealtimeSession(
        apiKey = NetContract.KEY,
        transport = transport,
        startTimeoutMs = 2_000L,
        commitTimeoutMs = 2_000L,
    )

    @Test
    fun `start ouvre le socket avec la clé en en-tête et attend session_started`() = runBlocking {
        val transport = ContractWsTransport()
        val starting = async { startLive(transport).start() }
        awaitOpen(transport)

        assertEquals(
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime" +
                "?model_id=scribe_v2_realtime&commit_strategy=manual&audio_format=pcm_16000",
            transport.openedUrl,
        )
        assertEquals(NetContract.KEY, transport.openedHeaders["xi-api-key"])
        transport.deliver("""{"message_type":"session_started","session_id":"s1","config":{}}""")

        starting.await().getOrThrow()
    }

    @Test
    fun `clé absente refuse sans réseau`() = runBlocking {
        val transport = ContractWsTransport()
        val session = ScribeRealtimeSession(apiKey = "", transport = transport)

        NetContract.assertError(session.start(), ApiError.InvalidKey::class.java, "clé absente")
        assertEquals(null, transport.openedUrl)
    }

    @Test
    fun `ouverture refusée donne Network`() = runBlocking {
        val transport = ContractWsTransport().apply { openResult = false }

        NetContract.assertError(startLive(transport).start(), ApiError.Network::class.java, "ouverture refusée")
    }

    @Test
    fun `silence du serveur donne Timeout`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport, startTimeoutMs = 60L)

        NetContract.assertError(session.start(), ApiError.Timeout::class.java, "session non démarrée")
        assertFalse(session.isLive)
    }

    @Test
    fun `le PCM avant session_started est tamponné puis flushé en trames de 100 ms`() = runBlocking {
        val transport = ContractWsTransport()
        val session = startLive(transport)
        val starting = async { session.start() }
        awaitOpen(transport)

        assertTrue(session.sendPcm(ByteArray(7_000) { (it % 251).toByte() }))
        assertTrue("rien ne part avant session_started", transport.sent.isEmpty())
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        assertTrue(session.isLive)
        assertEquals("6400 en 2 trames, 600 retenus pour le commit", 2, transport.sent.size)
        transport.sent.forEach { raw ->
            val chunk = JSONObject(raw)
            assertEquals(3_200, Base64.getDecoder().decode(chunk.getString("audio_base_64")).size)
            assertFalse(chunk.getBoolean("commit"))
        }
        transport.answerWith { committed("ok") }
        assertEquals("ok", session.commitAndFinal().getOrThrow())
        val tail = JSONObject(transport.sent.last())
        assertTrue(tail.getBoolean("commit"))
        assertEquals(600, Base64.getDecoder().decode(tail.getString("audio_base_64")).size)
    }

    @Test
    fun `un multiple exact de trame garde une trame pour le commit`() = runBlocking {
        val transport = ContractWsTransport()
        val session = startLive(transport)
        val starting = async { session.start() }
        awaitOpen(transport)
        session.sendPcm(ByteArray(6_400))
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        assertEquals("une trame partie, une retenue (jamais de commit vide)", 1, transport.sent.size)
        transport.answerWith { committed("ok") }
        session.commitAndFinal().getOrThrow()
        val tail = JSONObject(transport.sent.last())
        assertTrue(tail.getBoolean("commit"))
        assertEquals(3_200, Base64.getDecoder().decode(tail.getString("audio_base_64")).size)
    }

    @Test
    fun `les partiels sont transmis et le committed horodaté accepté`() = runBlocking {
        val transport = ContractWsTransport()
        val partials = mutableListOf<String>()
        val session = session(transport, partials = partials)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        transport.deliver("""{"message_type":"partial_transcript","text":"where is"}""")
        transport.deliver("""{"message_type":"partial_transcript","text":"where is the"}""")
        assertEquals(listOf("where is", "where is the"), partials)

        session.sendPcm(ByteArray(100))
        transport.answerWith {
            listOf("""{"message_type":"committed_transcript_with_timestamps","text":"done","words":[]}""")
        }
        assertEquals("done", session.commitAndFinal().getOrThrow())
        assertTrue("le commit ferme le socket", transport.closed)
        assertFalse(session.isLive)
    }

    @Test
    fun `un refus avant session_started échoue le start sans signaler deux fois`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)

        transport.deliver("""{"message_type":"auth_error","error":"bad key"}""")
        NetContract.assertError(starting.await(), ApiError.InvalidKey::class.java, "auth_error")
        assertTrue("session jamais née : pas de signal de mort", deaths.isEmpty())
        assertFalse(session.sendPcm(ByteArray(100)))
    }

    @Test
    fun `une erreur en pleine tenue signale une seule fois puis le commit échoue`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        transport.deliver("""{"message_type":"queue_overflow","error":"too fast"}""")
        transport.disconnect(java.io.IOException("coupe"))
        assertEquals("erreur trame + fermeture = un seul signal", 1, deaths.size)
        assertTrue(deaths.single() is ApiError.BadResponse)
        assertFalse(session.isLive)
        NetContract.assertError(session.commitAndFinal(), ApiError.Network::class.java, "commit après mort")
    }

    @Test
    fun `une fermeture propre après commit ne signale pas d'erreur`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()
        session.sendPcm(ByteArray(100))
        transport.answerWith { committed("ok") }

        session.commitAndFinal().getOrThrow()
        transport.disconnect(null)

        assertTrue(deaths.isEmpty())
    }

    @Test
    fun `un commit sans audio donne audio vide sans réseau`() = runBlocking {
        val transport = ContractWsTransport()
        val session = startLive(transport)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        val result = session.commitAndFinal()
        NetContract.assertError(result, ApiError.BadResponse::class.java, "audio vide")
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `un commit sans réponse donne Timeout`() = runBlocking {
        val transport = ContractWsTransport()
        val session = session(transport, commitTimeoutMs = 60L)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()
        session.sendPcm(ByteArray(100))

        NetContract.assertError(session.commitAndFinal(), ApiError.Timeout::class.java, "commit muet")
    }

    @Test
    fun `abort ferme sans commit ni signal`() = runBlocking {
        val transport = ContractWsTransport()
        val deaths = mutableListOf<ApiError>()
        val session = session(transport, deaths = deaths)
        val starting = async { session.start() }
        awaitOpen(transport)
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        starting.await().getOrThrow()

        session.abort()

        assertTrue(transport.closed)
        assertTrue(transport.sent.isEmpty())
        assertTrue(deaths.isEmpty())
        assertFalse(session.isLive)
    }

    @Test
    fun `un tampon plein avant session_started refuse l'audio`() = runBlocking {
        val transport = ContractWsTransport()
        val session = startLive(transport)
        val starting = async { session.start() }
        awaitOpen(transport)

        assertFalse(session.sendPcm(ByteArray(ScribeRealtimeSession.PENDING_CAP_BYTES + 1)))
        transport.deliver("""{"message_type":"session_started","session_id":"s1"}""")
        NetContract.assertError(starting.await(), ApiError.BadResponse::class.java, "tampon plein")
    }

    private fun committed(text: String): List<String> =
        listOf("""{"message_type":"committed_transcript","text":"$text"}""")
}
