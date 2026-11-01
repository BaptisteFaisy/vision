package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transport WS binaire M1, testé **sans réseau** : contrat d'octets (longueur big-endian,
 * en-tête sans image, JPEG brut), envoi binaire par défaut (`binaryWs`, opt-out = texte)
 * et repli JSON quand le serveur répond `{"id":-1,"error":…}`.
 */
class WsLiveBinaryTest {

    private val base = "https://sam.example"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)

    /** Longueur N lue en tête de trame (uint32 big-endian), oracle indépendant. */
    private fun headerLengthOf(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    /** En-tête JSON d'une trame, relu à la main (jamais via le code de production). */
    private fun headerOf(frame: ByteArray): JSONObject {
        val length = headerLengthOf(frame)
        return JSONObject(frame.copyOfRange(4, 4 + length).toString(Charsets.UTF_8))
    }

    /** `id` d'une trame binaire, pour que le faux serveur corrèle ses réponses. */
    private fun binaryIdOf(frame: ByteArray): Int = headerOf(frame).getInt("id")

    @Test
    fun `le message binaire respecte le contrat (longueur, en-tete, JPEG)`() {
        val frame = frameMessageBinary(7, jpeg, listOf("person", "door"), 640, 4)

        val headerBytes = frame.copyOfRange(4, 4 + headerLengthOf(frame))
        assertEquals("N = longueur de l'en-tête", headerBytes.size, headerLengthOf(frame))
        assertEquals(4 + headerBytes.size + jpeg.size, frame.size)
        val header = JSONObject(headerBytes.toString(Charsets.UTF_8))
        assertEquals(7, header.getInt("id"))
        assertEquals("person", header.getJSONArray("prompts").getString(0))
        assertEquals("door", header.getJSONArray("prompts").getString(1))
        assertEquals(640, header.getInt("work_edge"))
        assertEquals(4, header.getInt("max_objects"))
        assertEquals("bits", header.getString("mask_encoding"))
        assertFalse("pas d'image dans l'en-tête", header.has("image_b64"))
        assertTrue("le JPEG suit en brut", frame.copyOfRange(4 + headerBytes.size, frame.size).contentEquals(jpeg))
    }

    @Test
    fun `l en-tete binaire porte les memes knobs que le JSON`() {
        val text = JSONObject(liveFrameJson("QQ==", null, 640, 4, id = 5, maskFormat = "rle", maskEdge = 256, withDepth = true))
        val header = JSONObject(liveFrameHeaderJson(5, null, 640, 4, maskFormat = "rle", maskEdge = 256, withDepth = true))

        header.keys().forEach { key -> assertEquals("knob $key", text.get(key).toString(), header.get(key).toString()) }
        assertFalse(header.has("image_b64"))
        val none = JSONObject(liveFrameHeaderJson(6, null, 640, 4, masks = false))
        assertEquals("none", none.getString("mask_encoding"))
        assertFalse("pas de mask_format sans masque", none.has("mask_format"))
    }

    @Test
    fun `par defaut la frame part en binaire`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { bytes -> listOf(NetContract.frameReply(binaryIdOf(bytes))) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(1, transport.sentBytes.size)
        assertTrue("aucun texte par défaut", transport.sent.isEmpty())
    }

    @Test
    fun `avec binaryWs false la frame part en texte`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(1, transport.sent.size)
        assertTrue("aucune trame binaire en opt-out", transport.sentBytes.isEmpty())
    }

    @Test
    fun `avec binaryWs la frame part en binaire et la reponse devient LiveFrame`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { bytes -> listOf(NetContract.frameReply(binaryIdOf(bytes))) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        val frame = client.live("SUVLFw==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals(1, transport.sentBytes.size)
        assertTrue("aucun texte quand le binaire passe", transport.sent.isEmpty())
        assertEquals(1, headerOf(transport.sentBytes.single()).getInt("id"))
    }

    @Test
    fun `un base64 illisible en binaire donne BadResponse sans ouvrir le socket`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { listOf(NetContract.frameReply(1)) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        val failure = client.live("!!!pas du base64!!!", null, 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertFalse("socket jamais ouvert", transport.opened)
        assertTrue(transport.sentBytes.isEmpty())
    }

    @Test
    fun `une erreur id -1 fait repartir la frame en JSON`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
            .apply { answerBytesWith { listOf(NetContract.errorReply(-1)) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        val frame = client.live("SUVLFw==", listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals("un seul essai binaire", 1, transport.sentBytes.size)
        val retry = JSONObject(transport.sent.single())
        assertEquals("le repli garde l'id", 1, retry.getInt("id"))
        assertEquals("SUVLFw==", retry.getString("image_b64"))
    }

    @Test
    fun `apres un refus -1 les frames suivantes partent directement en JSON`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
            .apply { answerBytesWith { listOf(NetContract.errorReply(-1)) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()
        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals("un seul essai binaire par session", 1, transport.sentBytes.size)
        assertEquals(2, transport.sent.size)
    }

    @Test
    fun `un orphelin -1 sans erreur ne declenche pas le repli`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { bytes -> listOf("""{"id":-1,"width":1}""", NetContract.frameReply(binaryIdOf(bytes))) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        val frame = client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertTrue("aucun repli JSON", transport.sent.isEmpty())
        assertEquals(1, transport.sentBytes.size)
    }

    @Test
    fun `un envoi binaire refuse donne Network sans repli`() = runBlocking {
        val transport = ContractWsTransport().apply { sendResult = false }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        val result = client.live("SUVLFw==", null, 640, 4)

        NetContract.assertError(result, ApiError.Network::class.java, "envoi binaire refusé")
        assertEquals(1, transport.sentBytes.size)
        assertTrue("pas de repli quand le socket refuse", transport.sent.isEmpty())
    }

    @Test
    fun `la premiere frame annonce le transport binaire, une seule fois`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { bytes -> listOf(NetContract.frameReply(binaryIdOf(bytes))) } }
        val legs = mutableListOf<Boolean>()
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), onBinaryChange = legs::add)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()
        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(listOf(true), legs)
    }

    @Test
    fun `la premiere frame annonce le transport texte en opt-out`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val legs = mutableListOf<Boolean>()
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false, onBinaryChange = legs::add)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals(listOf(false), legs)
    }

    @Test
    fun `un refus -1 annonce le repli texte apres le binaire initial`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
            .apply { answerBytesWith { listOf(NetContract.errorReply(-1)) } }
        val legs = mutableListOf<Boolean>()
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), onBinaryChange = legs::add)

        client.live("SUVLFw==", null, 640, 4).getOrThrow()
        client.live("SUVLFw==", null, 640, 4).getOrThrow()

        assertEquals("binaire puis repli, sans répétition", listOf(true, false), legs)
    }
}
