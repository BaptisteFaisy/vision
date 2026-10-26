package dev.sam3vision.core.mask

import java.util.Base64
import java.util.zip.Deflater

/**
 * Encodeur « M1 » de référence pour les tests, calqué sur
 * `imaging.py::encode_mask_bits_b64` : magic `M1`, largeur et hauteur uint16, puis un flux
 * zlib des lignes du masque empaquetées 1 bit/pixel (bit de poids fort en tête, chaque ligne
 * complétée à l'octet par des zéros).
 *
 * Un seul exemplaire de cette logique : `MaskCoreTest` et `MaskRobustnessTest` s'en servent
 * tous les deux (`M1.b64(...)`), et les cas tordus se fabriquent avec [header] + [b64], donc
 * sans seconde implémentation de l'encodeur.
 */
internal object M1 {

    const val MAGIC_M: Byte = 0x4D
    const val MAGIC_1: Byte = 0x31

    /** Magic du format : `M1`. */
    fun magic(): ByteArray = byteArrayOf(MAGIC_M, MAGIC_1)

    /** Corps empaqueté **non compressé** : `ceil(width / 8)` octets par ligne. */
    fun packed(width: Int, height: Int, objet: (Int, Int) -> Boolean): ByteArray {
        val rowBytes = (width + 7) / 8
        val body = ByteArray(rowBytes * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (objet(x, y)) {
                    val at = y * rowBytes + (x shr 3)
                    body[at] = (body[at].toInt() or (1 shl (7 - (x and 7)))).toByte()
                }
            }
        }
        return body
    }

    /**
     * En-tête seul : magic (remplaçable pour les cas hostiles) puis les deux uint16.
     * `littleEndian = false` reproduit la première salve du serveur, qui les écrivait en
     * big-endian.
     */
    fun header(
        width: Int,
        height: Int,
        littleEndian: Boolean = true,
        magic: ByteArray = magic(),
    ): ByteArray {
        val wide = byteArrayOf((width and 0xFF).toByte(), ((width shr 8) and 0xFF).toByte())
        val high = byteArrayOf((height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte())
        return if (littleEndian) magic + wide + high else magic + wide.reversedArray() + high.reversedArray()
    }

    /** Payload base64 = [header] + zlib([body]) : exactement ce que reçoit le client. */
    fun b64(header: ByteArray, body: ByteArray): String = b64Raw(header, zlib(body))

    /** Payload base64 = [header] + [body] tel quel : pour les corps déjà compressés (ou tronqués). */
    fun b64Raw(header: ByteArray, body: ByteArray): String =
        Base64.getEncoder().encodeToString(header + body)

    /** Cas nominal : le masque décrit par [objet], en-tête little-endian (contrat) ou big-endian. */
    fun b64(
        width: Int,
        height: Int,
        objet: (Int, Int) -> Boolean,
        littleEndian: Boolean = true,
    ): String = b64(header(width, height, littleEndian), packed(width, height, objet))

    /** Les niveaux 0/1 d'une ligne du masque décodé : lecture unique pour tous les tests. */
    fun row(mask: GrayMask, y: Int): List<Int> = (0 until mask.width).map { x -> if (mask.isObject(x, y)) 1 else 0 }

    /** Une ligne du masque en « 10110… » : les motifs des vecteurs du backend, lisibles. */
    fun rowText(mask: GrayMask, y: Int): String = row(mask, y).joinToString(separator = "")

    /** `zlib.compress` de Python : le flux que lit `java.util.zip.Inflater`. */
    fun zlib(body: ByteArray, nowrap: Boolean = false): ByteArray {
        val deflater = Deflater(-1, nowrap)
        return try {
            deflater.setInput(body)
            deflater.finish()
            var out = ByteArray(body.size / 2 + 64)
            var written = 0
            while (!deflater.finished()) {
                if (written == out.size) out = out.copyOf(out.size * 2)
                written += deflater.deflate(out, written, out.size - written)
            }
            out.copyOf(written)
        } finally {
            deflater.end()
        }
    }
}