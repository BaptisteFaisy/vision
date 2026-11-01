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
 * Chemin tout-binaire (O6), testé **sans réseau** : `liveBytes` envoie le JPEG brut
 * en trame M1 sans construire ni décoder de base64, le repli JSON restant paresseux.
 * Le pont par défaut ([LiveClient.liveBytes]) et l'envoi immédiat (aucun tampon
 * applicatif entre l'appel et le socket) sont prouvés ici aussi.
 */
class WsLiveBytesTest {

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

    /** Oracle indépendant : l'encodeur du JDK, pas celui de la prod. */
    private fun expectedB64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `liveBytes envoie le JPEG brut en binaire sans texte`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { bytes -> listOf(NetContract.frameReply(headerOf(bytes).getInt("id"))) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val frame = client.liveBytes(jpeg, listOf("person"), 640, 4).getOrThrow()

        assertEquals(512, frame.width)
        assertEquals(1, transport.sentBytes.size)
        assertTrue("aucun texte quand le binaire passe", transport.sent.isEmpty())
        val sent = transport.sentBytes.single()
        assertEquals(1, headerOf(sent).getInt("id"))
        val payload = sent.copyOfRange(4 + headerLengthOf(sent), sent.size)
        assertTrue("le JPEG brut suit l'en-tête, octet pour octet", payload.contentEquals(jpeg))
    }

    @Test
    fun `liveBytes part immediatement sans attendre la reponse`() = runBlocking {
        val transport = ContractWsTransport()
        val client = WsLiveClient(base, transport, 5_000, RecordingLiveClient())

        val job = async { client.liveBytes(jpeg, null, 640, 4) }
        val deadline = System.currentTimeMillis() + 2_000L
        while (transport.sentBytes.isEmpty() && System.currentTimeMillis() < deadline) delay(10)

        assertEquals("la trame est partie avant toute réponse", 1, transport.sentBytes.size)
        transport.deliver(NetContract.frameReply(headerOf(transport.sentBytes.single()).getInt("id")))
        assertEquals(512, job.await().getOrThrow().width)
    }

    @Test
    fun `un JPEG vide donne BadResponse sans ouvrir le socket`() = runBlocking {
        val transport = ContractWsTransport()
            .apply { answerBytesWith { listOf(NetContract.frameReply(1)) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient())

        val failure = client.liveBytes(byteArrayOf(), null, 640, 4).exceptionOrNull()

        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
        assertFalse("socket jamais ouvert", transport.opened)
        assertTrue(transport.sentBytes.isEmpty())
    }

    @Test
    fun `avec binaryWs false liveBytes part en texte avec image_b64`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = false)

        client.liveBytes(jpeg, null, 640, 4).getOrThrow()

        assertEquals(1, transport.sent.size)
        assertTrue("aucune trame binaire en opt-out", transport.sentBytes.isEmpty())
        assertEquals(expectedB64(jpeg), JSONObject(transport.sent.single()).getString("image_b64"))
    }

    @Test
    fun `une erreur id -1 fait repartir les octets en JSON puis colle`() = runBlocking {
        val transport = ContractWsTransport { json -> listOf(NetContract.frameReply(json.getInt("id"))) }
            .apply { answerBytesWith { listOf(NetContract.errorReply(-1)) } }
        val client = WsLiveClient(base, transport, 1_000, RecordingLiveClient(), binaryWs = true)

        client.liveBytes(jpeg, null, 640, 4).getOrThrow()
        client.liveBytes(jpeg, null, 640, 4).getOrThrow()

        assertEquals("un seul essai binaire par session", 1, transport.sentBytes.size)
        assertEquals(2, transport.sent.size)
        assertEquals(expectedB64(jpeg), JSONObject(transport.sent.first()).getString("image_b64"))
    }

    @Test
    fun `le pont par defaut encode puis delegue a live`() = runBlocking {
        val client = RecordingLiveClient()

        client.liveBytes(jpeg, listOf("person"), 640, 4).getOrThrow()

        assertEquals(1, client.liveCalls)
        assertEquals(expectedB64(jpeg), client.lastJpeg)
    }
}
