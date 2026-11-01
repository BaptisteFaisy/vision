package dev.sam3vision.core.net

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trames WS binaires M1 côté client, prouvées **sans réseau** : l'encodeur
 * ([frameMessageBinary]) reproduit les vecteurs du contrat à l'octet près, le
 * préfixe est un uint32 big-endian qui compte des octets UTF-8, et les limites
 * tiennent (borne 4096, JPEG vide bien formé). Les trames attendues sont
 * recopiées en littéraux depuis la source unique (`m1_ws_vectors.json` v1, PR
 * vision#900) : ce test reste vert avec ou sans elle, et le faux serveur
 * ([ContractWsTransport]) prouve ce que le client pose vraiment sur le socket.
 */
class M1ClientVectorsTest {

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)

    /** Un vecteur émissible par le client : paramètres Kotlin + trame attendue. */
    private data class ClientVector(
        val name: String,
        val frameHex: String,
        val id: Int,
        val prompts: List<String>?,
        val workEdge: Int,
        val maxObjects: Int,
        val maskFormat: String?,
        val maskEdge: Int,
        val withDepth: Boolean,
        val masks: Boolean,
    )

    private val vectors = listOf(
        ClientVector(
            "valid-minimal",
            "0000005b7b226964223a372c2270726f6d707473223a5b22706572736f6e222c22646f6f72225d2c" +
                "22776f726b5f65646765223a3634302c226d61785f6f626a65637473223a342c226d61736b5f656e636f" +
                "64696e67223a2262697473227dffd8010203",
            7, listOf("person", "door"), 640, 4, null, 0, false, true,
        ),
        ClientVector(
            "valid-null-prompts-none",
            "0000004e7b226964223a392c2270726f6d707473223a6e756c6c2c22776f726b5f65646765223a3634302c" +
                "226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a226e6f6e65227dffd8010203",
            9, null, 640, 4, null, 0, false, false,
        ),
        ClientVector(
            "valid-utf8-octets",
            "000000567b226964223a31312c2270726f6d707473223a5b227069c3a9746f6e225d2c22776f726b5f656467" +
                "65223a3634302c226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a2262697473227d" +
                "ffd8010203",
            11, listOf("piéton"), 640, 4, null, 0, false, true,
        ),
        ClientVector(
            "valid-full-knobs",
            "000000857b226964223a31322c2270726f6d707473223a6e756c6c2c22776f726b5f65646765223a3634302c" +
                "226d61785f6f626a65637473223a342c226d61736b5f656e636f64696e67223a2262697473222c226d61736b5f6" +
                "66f726d6174223a22726c65222c226d61736b5f65646765223a3235362c22776974685f6465707468223a74727565" +
                "7dffd8010203",
            12, null, 640, 4, "rle", 256, true, true,
        ),
    )

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }

    /** UInt32 big-endian lue en tête de trame, oracle indépendant. */
    private fun be32(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    @Test
    fun `l encodeur reproduit les vecteurs du contrat a l octet pres`() {
        vectors.forEach { vector ->
            val frame = frameMessageBinary(
                vector.id, jpeg, vector.prompts, vector.workEdge, vector.maxObjects,
                vector.maskFormat, vector.maskEdge, vector.withDepth, vector.masks,
            )
            assertArrayEquals("trame ${vector.name}", hexToBytes(vector.frameHex), frame)
        }
    }

    @Test
    fun `le prefixe est big-endian et compte des octets utf-8`() {
        val frame = hexToBytes(vectors.single { it.name == "valid-utf8-octets" }.frameHex)
        val header = frame.copyOfRange(4, 4 + be32(frame))
        assertEquals("N = 86 octets", 86, be32(frame))
        assertEquals("N = longueur de l'en-tête", header.size, be32(frame))
        val chars = header.toString(Charsets.UTF_8).length
        assertTrue("é compte 2 octets pour 1 char ($chars chars < 86 octets)", chars < header.size)
        assertEquals("piéton survit à l'aller", "piéton", JSONObject(header.toString(Charsets.UTF_8)).getJSONArray("prompts").getString(0))
    }

    @Test
    fun `aucune trame valide ne porte image_b64`() {
        vectors.forEach { vector ->
            val frame = hexToBytes(vector.frameHex)
            val header = frame.copyOfRange(4, 4 + be32(frame)).toString(Charsets.UTF_8)
            assertFalse("image_b64 dans ${vector.name}", header.contains("image_b64"))
        }
    }

    @Test
    fun `un en-tete de 4096 octets part avec N egal 4096`() {
        // `null` → `["…"]` allonge l'en-tête d'exactement la longueur du prompt :
        // bourrage calibré à l'octet près (que de l'ASCII, 1 char = 1 octet).
        val base = liveFrameHeaderJson(21, null, 640, 4, masks = false).toByteArray(Charsets.UTF_8).size
        val prompt = " ".repeat(4096 - base)
        val frame = frameMessageBinary(21, jpeg, listOf(prompt), 640, 4, masks = false)

        assertEquals("borne incluse", 4096, be32(frame))
        assertEquals(4 + 4096 + jpeg.size, frame.size)
    }

    @Test
    fun `au-dela de 4096 le client encode fidelement, le serveur refuse`() {
        // Pas de garde côté client : un en-tête > 4 Ko ne part qu'avec des prompts
        // déraisonnables, et le serveur le refuse en 400 connexion gardée (contrat
        // `err-n-too-large`). Le préfixe reste la vraie longueur, jamais tronquée.
        val base = liveFrameHeaderJson(21, null, 640, 4, masks = false).toByteArray(Charsets.UTF_8).size
        val frame = frameMessageBinary(21, jpeg, listOf(" ".repeat(4097 - base)), 640, 4, masks = false)

        assertEquals(4097, be32(frame))
        assertEquals(4 + 4097 + jpeg.size, frame.size)
    }

    @Test
    fun `un JPEG vide donne une trame bien formee sans image`() {
        // Le serveur répond 400 en gardant l'id (`err-jpeg-empty`) : la trame reste
        // décodable (N + en-tête, zéro octet d'image), jamais une trame tronquée.
        val frame = frameMessageBinary(13, ByteArray(0), null, 640, 4, masks = false)
        val header = frame.copyOfRange(4, 4 + be32(frame)).toString(Charsets.UTF_8)

        assertEquals(13, JSONObject(header).getInt("id"))
        assertEquals(4 + be32(frame), frame.size)
    }

    @Test
    fun `le client pose sur le socket exactement la trame du contrat`() = runBlocking {
        // Oracle indépendant : en-tête littéral + préfixe assemblés à la main, jamais
        // via l'encodeur de production. Première frame d'un client neuf → `id` 1.
        val header = "{\"id\":1,\"prompts\":[\"person\",\"door\"],\"work_edge\":640,\"max_objects\":4,\"mask_encoding\":\"bits\"}"
        val headerBytes = header.toByteArray(Charsets.UTF_8)
        val expected = byteArrayOf(0, 0, 0, headerBytes.size.toByte()) + headerBytes + jpeg
        val transport = ContractWsTransport()
            .apply { answerBytesWith { listOf(NetContract.frameReply(1)) } }
        val client = WsLiveClient("https://sam.example", transport, 1_000, RecordingLiveClient())

        client.live("/9gBAgM=", listOf("person", "door"), 640, 4).getOrThrow()

        assertArrayEquals(expected, transport.sentBytes.single())
    }
}
