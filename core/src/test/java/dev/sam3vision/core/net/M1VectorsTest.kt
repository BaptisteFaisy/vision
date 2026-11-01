package dev.sam3vision.core.net

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vecteurs de contrat WS binaire M1, lus depuis la source unique partagée avec
 * le serveur (`server/scripts/tests_units/m1_ws_vectors.json`, doc
 * `docs/LATENCE-M1-VECTEURS.md`) : l'encodeur client doit reproduire chaque
 * trame valide à l'octet près — préfixe big-endian, en-tête sans image, JPEG.
 */
class M1VectorsTest {

    /** Document des vecteurs, trouvé en remontant depuis le dossier de test. */
    private fun vectorsDoc(): JSONObject {
        val start = File(System.getProperty("user.dir")).absoluteFile
        val found = generateSequence(start) { it.parentFile }
            .map { File(it, "server/scripts/tests_units/m1_ws_vectors.json") }
            .firstOrNull { it.isFile }
        checkNotNull(found) { "m1_ws_vectors.json introuvable depuis " + System.getProperty("user.dir") }
        return JSONObject(found.readText())
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }

    /** UInt32 big-endian lue en tête de trame, oracle indépendant. */
    private fun be32(frame: ByteArray): Int =
        ((frame[0].toInt() and 0xFF) shl 24) or ((frame[1].toInt() and 0xFF) shl 16) or
            ((frame[2].toInt() and 0xFF) shl 8) or (frame[3].toInt() and 0xFF)

    private fun vectors(): List<JSONObject> {
        val array = vectorsDoc().getJSONArray("vectors")
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    @Test
    fun `l encodeur reproduit les trames valides a l octet pres`() {
        var checked = 0
        vectors().filter { !it.isNull("kotlin") }.forEach { vector ->
            val params = vector.getJSONObject("kotlin")
            val promptsArray = if (params.isNull("prompts")) null else params.getJSONArray("prompts")
            val prompts = promptsArray?.let { array -> (0 until array.length()).map { i -> array.getString(i) } }
            val frame = frameMessageBinary(
                params.getInt("id"), hexToBytes(vector.getString("jpeg_hex")), prompts,
                params.getInt("workEdge"), params.getInt("maxObjects"),
                if (params.isNull("maskFormat")) null else params.getString("maskFormat"),
                params.getInt("maskEdge"), params.getBoolean("withDepth"), params.getBoolean("masks"),
            )
            assertArrayEquals(
                "trame ${vector.getString("name")}",
                hexToBytes(vector.getString("frame_hex")), frame,
            )
            checked++
        }
        assertTrue("aucun vecteur client vérifié", checked > 0)
    }

    @Test
    fun `le prefixe est big-endian et compte des octets utf-8`() {
        val utf8 = vectors().single { it.getString("name") == "valid-utf8-octets" }
        val frame = hexToBytes(utf8.getString("frame_hex"))
        val headerBytes = hexToBytes(utf8.getString("header_hex"))
        assertEquals("N = longueur en octets", headerBytes.size, be32(frame))
        val littleEndian = ((frame[3].toInt() and 0xFF) shl 24) or
            ((frame[2].toInt() and 0xFF) shl 16) or ((frame[1].toInt() and 0xFF) shl 8) or
            (frame[0].toInt() and 0xFF)
        assertFalse("pas du little-endian", littleEndian == headerBytes.size)
        val chars = headerBytes.toString(Charsets.UTF_8).length
        assertTrue("é compte 2 octets, 1 char ($chars chars < ${headerBytes.size} octets)", chars < headerBytes.size)
    }

    @Test
    fun `aucune trame valide ne porte image_b64`() {
        vectors().filter { it.getString("kind") == "valid" && it.optString("header_hex", "").isNotEmpty() }
            .forEach { vector ->
                val header = hexToBytes(vector.getString("header_hex")).toString(Charsets.UTF_8)
                assertFalse("image_b64 dans ${vector.getString("name")}", header.contains("image_b64"))
            }
    }
}
