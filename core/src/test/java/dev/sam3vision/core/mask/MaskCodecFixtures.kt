package dev.sam3vision.core.mask

import java.util.Base64

/**
 * Fixtures du codec masques, calquées sur `mask_codec.py` : ce que le serveur écrit, les
 * tests l'écrivent ici — **une seule implémentation de l'encodeur de test**, partagée par
 * [MaskRleTest], [MaskPolyTest], [MaskDecodeTest] et [MaskBenchTest]. Aucun de ces octets ne
 * part dans l'APK : c'est du code de test.
 */
internal object Rle {

    /** Runs LEB128 d'un raster **0/255** : passe 1 = fond, passe 2 = objet, etc. */
    fun encode(levels: IntArray): ByteArray {
        if (levels.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>(64)
        var current = levels[0]
        var count = 0
        for (level in levels + SENTINEL) {
            if (level == current) {
                count++
            } else {
                appendVarint(out, count)
                current = level
                count = 1
            }
        }
        return out.toByteArray()
    }

    /** Base64 **strict** du corps, comme le champ `mask_b64` de la réponse. */
    fun b64(runs: ByteArray): String = Base64.getEncoder().encodeToString(runs)

    /** Un varint LEB128 non signé : 7 bits par octet, bit de poids fort = « il en reste ». */
    fun appendVarint(out: MutableList<Byte>, value: Int) {
        var rest = value
        while (true) {
            val octet = rest and 0x7F
            rest = rest ushr 7
            if (rest == 0) {
                out.add(octet.toByte())
                return
            }
            out.add((octet or 0x80).toByte())
        }
    }

    /** Niveau sentinelle : jamais 0 ni 255, donc il ferme toujours le dernier run. */
    private val SENTINEL = intArrayOf(-1)
}

/** Rasters de test : les formes analytiques que le serveur envoie (disque, rectangle, anneau). */
internal object Shapes {

    /** Raster `width × height` : 255 là où [objet] est vrai, 0 ailleurs. */
    fun raster(width: Int, height: Int, objet: (Int, Int) -> Boolean): IntArray =
        IntArray(width * height) { i -> if (objet(i % width, i / width)) 255 else 0 }

    /** Disque de rayon [radius] centré : la forme pleine du banc. */
    fun disk(width: Int, height: Int, radius: Int): IntArray = raster(width, height) { x, y ->
        val dx = x - width / 2
        val dy = y - height / 2
        dx * dx + dy * dy <= radius * radius
    }
}