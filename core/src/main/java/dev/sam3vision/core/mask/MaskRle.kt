package dev.sam3vision.core.mask

/**
 * Décodage du format `rle` du codec masques (`mask_format: "rle"`, backend
 * `mask_codec.py::encode_mask`) : des **runs LEB128** lus dans l'ordre des pixels (ligne
 * majeure), le premier run étant du **fond**. Les niveaux alternent donc 0, 255, 0, 255…
 * et la somme des runs doit valoir exactement `largeur × hauteur` : c'est la garde
 * d'intégrité (un masque tronqué est refusé plutôt que dessiné de travers) **et** la raison
 * pour laquelle le client a besoin de la grille annoncée par `mask_scale` — les runs ne
 * portent aucune dimension.
 *
 * Le format est **sans perte** : ce que `encode_mask` a lu du raster, on le relit niveau
 * par niveau. `null` — jamais d'exception — si le base64, un varint ou la somme des runs ne
 * collent pas : l'appelant dessine alors la boîte, comme pour un masque `"none"`.
 */
fun decodeRleMask(maskB64: String, width: Int, height: Int): GrayMask? {
    val runs = base64Bytes(maskB64) ?: return null
    return decodeRleBytes(runs, width, height)
}

/** [decodeRleMask] sans base64 : les runs bruts, tels que les tests les fabriquent. */
fun decodeRleBytes(runs: ByteArray, width: Int, height: Int): GrayMask? {
    val total = maskGridSize(width, height) ?: return null
    if (runs.isEmpty()) return null
    val pixels = ByteArray(total)
    var at = 0
    var level = 0
    var index = 0
    while (index < runs.size) {
        val run = readVarint(runs, index) ?: return null
        index = run.second
        val count = run.first
        if (count > total - at) return null
        if (level != 0 && count > 0) java.util.Arrays.fill(pixels, at, at + count, level.toByte())
        at += count
        level = if (level == 0) 255 else 0
    }
    return if (at == total) GrayMask(width, height, pixels) else null
}

/**
 * Varint LEB128 non signé (7 bits par octet, bit de poids fort = « il en reste »).
 * Borné à cinq octets et refusé au-delà de la capacité d'un `Int` : un flux hostile ne peut
 * ni faire boucler la lecture ni déborder en silence. Renvoie la valeur et l'index suivant.
 */
private fun readVarint(bytes: ByteArray, from: Int): Pair<Int, Int>? {
    var value = 0
    var shift = 0
    var index = from
    while (index < bytes.size) {
        val octet = bytes[index].toInt() and 0xFF
        index++
        if (shift > 28 || (shift == 28 && octet and 0x7F > 7)) return null
        value = value or ((octet and 0x7F) shl shift)
        if (octet and 0x80 == 0) return value to index
        shift += 7
    }
    return null
}