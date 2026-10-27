package dev.sam3vision.core.net

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct par WebSocket, testé **sans réseau** : [ContractWsTransport] capture les messages
 * envoyés et injecte les réponses du serveur. Le serveur répond de façon synchrone dans
 * `send`, exactement comme un socket chaud — le client enregistre l'attente avant d'envoyer.
 */
class WsLiveClientTest {

    private val base = "https://sam.example"

    /**
     * Compteur de lectures du chemin WebSocket : l'`id` se lit en tête **sans** construire de
     * JSON, et la trame retenue n'est convertie en `JSONObject` **qu'une seule fois** (la garde
     * `error` et la frame partagent ensuite cet objet).
     */
    private class CountingReader {
        var idReads = 0
            private set
        var jsonReads = 0
            private set

        fun readId(text: String): Int {
            idReads += 1
            return leadingId(text)
        }

        fun readJson(text: String): JSONObject? {
            jsonReads += 1
            return parse(text)
        }
    }

    /** Masque `bits` d'un objet, en base64 : ~1,1 Ko par objet, comme une vraie réponse. */
    private fun bigMask(): String = "A".repeat(MASK_B64_CHARS)

    /** Réponse réaliste de ~5 Ko : 4 objets avec masques, `id` en tête (contrat serveur). */
    private fun bigFrameReply(id: Int): String {
        val objects = (1..4).joinToString(",") {
            """{"label":"person","score":0.91,"box":[1,2,30,40],"mask_b64":"${bigMask()}"}"""
        }
        return """{"id":$id,"width":512,"height":1138,"elapsed_ms":118,"mask_encoding":"bits",""" +
            """"timings_ms":{"vision":41.5,"detr":22.1,"masks":5.3,"total":88.2},"objects":[$objects]}"""
    }

    private companion object {
        /** Tailles de masques d'une réponse réelle : la frame fait ~5 Ko, pas quelques octets. */
        const val MASK_B64_CHARS = 1150
    }

    @Test
    fun `le message envoye respecte le contrat`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.live("SUVLFw==", listOf("person", "door"), 640, 4).getOrThrow()

        val sent = JSONObject(transport.sent.single())
        assertEquals("SUVLFw==", sent.getString("image_b64"))
        assertEquals("person", sent.getJSONArray("prompts").getString(0))
        assertEquals("door", sent.getJSONArray("prompts").getString(1))
        assertEquals(4, sent.getInt("max_objects"))
        assertEquals(640, sent.getInt("work_edge"))
        assertEquals(1, sent.getInt("id"))
        assertEquals("wss://sam.example/live/ws", transport.openedUrl)
    }

    @Test
    fun `les jalons reseau datent en-tetes puis corps`() = runBlocking {
        // Le transport appelle onOpen au handshake et le client onBody à la réception :
        // la chronologie de l'écran peut alors séparer l'attente de la descente.
        val seen = mutableListOf<String>()
        val marks = object : WireMarks {
            override fun onHeaders() { seen += "headers" }
            override fun onBody() { seen += "body" }
        }
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), marks = marks)

        client.live("QQ==", null, 640, 4).getOrThrow()

        assertEquals(listOf("headers", "body"), seen)
    }

    @Test
    fun `un message orphelin ne pose pas de jalon corps`() = runBlocking {
        val seen = mutableListOf<String>()
        val marks = object : WireMarks {
            override fun onHeaders() { seen += "headers" }
            override fun onBody() { seen += "body" }
        }
        // Réponse portant un id qu'aucune requête n'attend : ignorée, donc non datée.
        val transport = ContractWsTransport { listOf("{\"id\":99,\"elapsed_ms\":1}") }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), marks = marks)

        client.live("QQ==", null, 640, 4)

        assertEquals(listOf("headers"), seen)
    }

    @Test
    fun `le message WS demande des masques bits, comme le POST`() = runBlocking {
        // Un seul corps de frame pour les deux transports : le champ `mask_encoding` ne
        // doit pas exister sur l'un et pas sur l'autre (sinon le WS resterait en PNG).
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.live("QQ==", null, 640, 4).getOrThrow()

        val sent = JSONObject(transport.sent.single())
        assertEquals("bits", sent.getString("mask_encoding"))
        assertTrue("prompts null doit rester null", sent.isNull("prompts"))
    }

    @Test
    fun `la reponse du serveur devient une LiveFrame`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals(1138, frame.height)
        assertEquals(118L, frame.elapsedMs)
        val obj = frame.objects.single()
        assertEquals("person", obj.label)
        assertEquals(0.91f, obj.score, 0.001f)
        assertEquals(listOf(1f, 2f, 30f, 40f), obj.box)
        assertEquals("QUJD", obj.maskPngB64)
    }

    @Test
    fun `la reponse porte l encodage applique et les chronos serveur`() = runBlocking {
        val transport = ContractWsTransport { json ->
            listOf(
                """{"id":${json.getInt("id")},"width":512,"height":1138,"elapsed_ms":88,
                    "mask_encoding":"bits","timings_ms":{"vision":41.5,"total":88.2},
                    "objects":[{"label":"person","score":0.9,"box":[1,2,3,4],"mask_b64":"TTEIAAIA"}]}""",
            )
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals("bits", frame.maskEncoding)
        assertEquals(41.5, frame.timingsMs.getValue("vision"), 0.0001)
        assertEquals(88L, frame.elapsedMs)
    }

    /** Backend sans `FAST_TIMING` : pas de `timings_ms`, et `mask_encoding` absent ⇒ `png`. */
    @Test
    fun `une reponse sans champs optionnels reste lisible`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", null, 640, 4).getOrThrow()

        assertEquals("png", frame.maskEncoding)
        assertTrue(frame.timingsMs.isEmpty())
    }

    @Test
    fun `un id non correle est ignore`() = runBlocking {
        // Réponse hors sujet (id inconnu, largeur 999) puis la bonne : seule la seconde compte.
        val transport = ContractWsTransport { json ->
            val id = json.getInt("id")
            listOf(NetContract.frameReply(id + 100, width = 999), NetContract.frameReply(id))
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
    }

    @Test
    fun `un message d erreur du serveur donne BadResponse`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.errorReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertTrue(failure?.message.orEmpty().contains("prompts inconnus"))
    }

    @Test
    fun `sans reponse le delai est depasse`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, timeoutMs = 120, http = RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
    }

    @Test
    fun `une fermeture du socket fait echouer la frame en vol`() = runBlocking {
        var transport: ContractWsTransport? = null
        transport = ContractWsTransport { _ ->
            transport?.disconnect(IOException("coupure"))
            emptyList()
        }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
    }

    @Test
    fun `un socket qui ne s ouvre pas donne Network`() = runBlocking {
        val transport = ContractWsTransport().apply { openResult = false }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu Network, obtenu $failure", failure is ApiError.Network)
        assertTrue(transport.sent.isEmpty())
    }

    @Test
    fun `close ferme le transport`() {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.close()

        assertTrue(transport.closed)
    }

    @Test
    fun `health est delegue au client HTTP`() = runBlocking {
        val http = RecordingLiveClient()
        val client = WsLiveClient(base, ContractWsTransport(), 1_000, http)

        val health = client.health().getOrThrow()

        assertEquals(SAMPLE_HEALTH, health)
        assertEquals(1, http.healthCalls)
    }

    @Test
    fun `isConnected_reflete_l_etat_du_socket`() = runBlocking {
        val transport = ContractWsTransport(fun(json: JSONObject): List<String> = listOf(NetContract.frameReply(json.getInt("id"))))
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        assertFalse(client.isConnected)
        client.live("AA==", listOf("person"), 640, 4).getOrThrow()
        assertTrue(client.isConnected)

        client.close()
        assertFalse(client.isConnected)
    }

    @Test
    fun `un silence du serveur referme le socket pour que la frame suivante le rouvre`() = runBlocking {
        var opens = 0
        val transport = object : WsTransport {
            override fun open(
                url: String,
                onText: (String) -> Unit,
                onClosed: (Throwable?) -> Unit,
                onOpen: () -> Unit,
            ): Boolean {
                opens += 1
                return true
            }

            override fun send(text: String): Boolean = true

            override fun close() {}
        }
        val client = WsLiveClient(base, transport, timeoutMs = 120, http = RecordingLiveClient())

        assertTrue(client.live("QQ==", listOf("person"), 640, 4).exceptionOrNull() is ApiError.Timeout)
        assertFalse("le socket muet doit être considéré fermé", client.isConnected)
        assertEquals(1, opens)

        client.live("QQ==", listOf("person"), 640, 4)
        assertEquals("la frame suivante doit retenter un handshake", 2, opens)
    }

    /** Client dont les deux lectures du chemin WebSocket sont comptées. */
    private fun countedClient(reader: CountingReader, transport: ContractWsTransport): WsLiveClient =
        WsLiveClient(
            base, transport, 1_000, RecordingLiveClient(),
            readId = reader::readId, readJson = reader::readJson,
        )

    @Test
    fun `une reponse n est convertie en JSON qu une seule fois`() = runBlocking {
        val reader = CountingReader()
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }

        val frame = countedClient(reader, transport).live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals("une trame, une lecture JSON", 1, reader.jsonReads)
        assertEquals("l'id se lit une fois, sans construire de JSON", 1, reader.idReads)
    }

    @Test
    fun `une reponse de 5 Ko avec masques n est lue qu une fois`() = runBlocking {
        val reader = CountingReader()
        val transport = ContractWsTransport { json -> listOf(bigFrameReply(json.getInt("id"))) }

        val frame = countedClient(reader, transport).live("QQ==", listOf("person"), 640, 4).getOrThrow()

        val raw = transport.received.single()
        assertTrue("réponse réaliste ~5 Ko attendue, obtenue ${raw.length} o", raw.length in 4900..5400)
        assertEquals(4, frame.objects.size)
        assertEquals("bits", frame.maskEncoding)
        assertEquals(MASK_B64_CHARS, frame.objects.first().maskPngB64.length)
        assertEquals("l'id se lit en tête, sans parser les 5 Ko", 1, leadingId(raw))
        assertEquals(1, reader.jsonReads)
        assertEquals(1, reader.idReads)
    }

    @Test
    fun `un message sans destinataire n est jamais converti en JSON`() = runBlocking {
        val reader = CountingReader()
        // Une grosse trame orpheline (id inconnu) passe avant la bonne : elle ne doit rien coûter.
        val transport = ContractWsTransport { json ->
            listOf(bigFrameReply(json.getInt("id") + 100), NetContract.frameReply(json.getInt("id")))
        }

        val frame = countedClient(reader, transport).live("QQ==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals("seule la trame corrélée est parsée", 1, reader.jsonReads)
        assertEquals("les deux id se lisent en tête, aucun JSON construit pour l'orpheline", 2, reader.idReads)
    }

    @Test
    fun `une reponse d erreur du serveur est lue une seule fois`() = runBlocking {
        val reader = CountingReader()
        val transport = ContractWsTransport { json -> listOf(NetContract.errorReply(json.getInt("id"))) }

        val failure = countedClient(reader, transport).live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertTrue(failure?.message.orEmpty().contains("400 : prompts inconnus"))
        assertEquals("la garde error consomme l'objet déjà parsé", 1, reader.jsonReads)
        assertEquals(1, reader.idReads)
    }

    @Test
    fun `une trame tronquee donne BadResponse apres une seule lecture`() = runBlocking {
        val reader = CountingReader()
        val transport = ContractWsTransport { json ->
            listOf("""{"id":${json.getInt("id")},"width":512,"objects":""")
        }

        val failure = countedClient(reader, transport).live("QQ==", listOf("person"), 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertTrue(failure?.message.orEmpty().contains("illisible"))
        assertEquals(1, reader.jsonReads)
    }

    @Test
    fun `la reponse tardive d une frame perimee ne reveille pas la frame suivante`() = runBlocking {
        val transport = ContractWsTransport()          // silence : la frame 1 expire, son id est oublié
        val client = WsLiveClient(base, transport, timeoutMs = 120, http = RecordingLiveClient())
        assertTrue(client.live("QQ==", null, 640, 4).exceptionOrNull() is ApiError.Timeout)

        transport.deliver(NetContract.frameReply(1, width = 999))     // la traîne arrive, pour une frame oubliée
        transport.answerWith { json -> listOf(NetContract.frameReply(json.getInt("id"))) }

        val frame = client.live("QQ==", null, 640, 4).getOrThrow()
        assertEquals("la trame périmée ne doit pas être prise pour la nouvelle", 512, frame.width)
    }

    /**
     * Transport dont le **premier** envoi dort jusqu'à [release] : prouve que l'envoi
     * (~28 Ko) ne tient pas le verrou du client — la seconde frame s'enregistre et
     * reçoit sa réponse pendant ce sommeil, au lieu de se figer derrière l'envoi.
     */
    private class BlockingSendTransport : WsTransport {
        private var onText: ((String) -> Unit)? = null
        private val sends = AtomicInteger(0)
        private val released = CountDownLatch(1)

        override fun open(
            url: String,
            onText: (String) -> Unit,
            onClosed: (Throwable?) -> Unit,
            onOpen: () -> Unit,
        ): Boolean {
            this.onText = onText
            onOpen()
            return true
        }

        override fun send(text: String): Boolean {
            if (sends.incrementAndGet() == 1) released.await(10, TimeUnit.SECONDS)
            return true
        }

        override fun close() {}

        fun deliver(message: String) {
            onText?.invoke(message)
        }

        fun awaitSends(count: Int, seconds: Long): Boolean {
            val deadline = System.currentTimeMillis() + seconds * 1000
            while (sends.get() < count && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return sends.get() >= count
        }

        fun release() = released.countDown()
    }

    @Test
    fun `l envoi d une frame ne bloque pas la reception des autres`() = runBlocking {
        val transport = BlockingSendTransport()
        val client = WsLiveClient(base, transport, 5_000, RecordingLiveClient())

        val first = async(Dispatchers.IO) { client.live("QQ==", null, 640, 4) }
        assertTrue("le premier envoi doit partir", transport.awaitSends(1, 5))
        val second = async(Dispatchers.IO) { client.live("QQ==", null, 640, 4) }
        assertTrue("le second envoi ne doit pas attendre le premier", transport.awaitSends(2, 5))

        transport.deliver(NetContract.frameReply(2))
        val frame = withTimeout(5_000) { second.await() }.getOrThrow()
        assertEquals(512, frame.width)

        transport.release()
        transport.deliver(NetContract.frameReply(1))
        val frame1 = withTimeout(5_000) { first.await() }.getOrThrow()
        assertEquals(512, frame1.width)
    }

    @Test
    fun `leadingId lit l id en tete sans parser la trame`() {
        assertEquals(7, leadingId(bigFrameReply(7)))
        assertEquals(7, leadingId("""{"id":7,"width":512}"""))
        assertEquals(7, leadingId("""{ "id" : 7 , "width":512 }"""))   // espacements tolérés
        assertEquals(7, leadingId("""{"width":512,"id":7}"""))         // id ailleurs au premier niveau
        assertEquals(-1, leadingId("""{"objects":[{"id":5}]}"""))      // id niché : pas le nôtre
        assertEquals(-1, leadingId("""{"id":null}"""))
        assertEquals(-1, leadingId("pas du json"))
    }
}
