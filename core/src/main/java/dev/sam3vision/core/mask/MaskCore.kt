package dev.sam3vision.core.mask

import java.util.Base64

/**
 * Masque gris 8 bits : 255 = objet, 0 = fond. Pur Kotlin (testable sur JVM),
 * la conversion en [android.graphics.Bitmap] vit dans Masks.kt.
 */
data class GrayMask(val width: Int, val height: Int, val pixels: ByteArray) {

    val size: Int get() = width * height

    fun levelAt(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) return 0
        return pixels[y * width + x].toInt() and 0xFF
    }

    fun isObject(x: Int, y: Int): Boolean = levelAt(x, y) >= 128
}

private const val IHDR_OFFSET = 12

/**
 * Format « M1 » du backend (`imaging.py::encode_mask_bits_b64`) : magic `M1`,
 * largeur uint16, hauteur uint16, puis zlib de lignes empaquetées 1 bit/pixel.
 * Le contrat écrit les tailles en **little-endian** ; la première salve les écrivait en
 * big-endian, donc [decodeBitsMask] tente les deux leçons (une image serveur déjà déployée
 * reste lisible au lieu de rendre un overlay mort).
 */
private const val BITS_MAGIC_0 = 'M'.code.toByte()
private const val BITS_MAGIC_1 = '1'.code.toByte()
private const val BITS_HEADER_SIZE = 6

/** Garde-fou d'allocation : le corps décompressé d'un masque ne dépasse jamais cette taille. */
private const val BITS_MAX_BODY_BYTES = 8 * 1024 * 1024

/**
 * Tampon de départ du décompresseur, plafonné : un masque du direct (640×360) tient dans
 * 28 800 octets et remplit donc pile son tampon, sans recopie ; un en-tête hostile qui
 * réclamerait 8 Mo n'alloue que ce quart de mégaoctet avant d'être rejeté.
 */
private const val BITS_INITIAL_BYTES = 64 * 1024

/**
 * Décode du base64 tolérant (espaces/retours ligne, préfixe `data:...;base64,`).
 * Le cas nominal — une chaîne propre — passe par le décodeur strict **sans aucune copie
 * intermédiaire** : le nettoyage (deux allocations de chaîne) n'a lieu que si la lecture
 * directe échoue. Sur 12 masques par frame, c'est une allocation de moins par masque.
 */
fun base64Bytes(text: String): ByteArray? {
    if (text.isEmpty()) return null
    val decoder = Base64.getDecoder()
    try {
        return decoder.decode(text)
    } catch (e: IllegalArgumentException) {
        // pas du base64 nu : on retente après nettoyage (préfixe data:, espaces)
    }
    val cleaned = text.substringAfter("base64,", text).filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return null
    return try {
        decoder.decode(cleaned)
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Lit largeur/hauteur dans l'en-tête IHDR d'un PNG (pur, sans décodage d'image). */
fun parsePngSize(bytes: ByteArray): Pair<Int, Int>? {
    if (bytes.size < IHDR_OFFSET + 8) return null
    if (bytes[1] != 'P'.code.toByte() || bytes[2] != 'N'.code.toByte() || bytes[3] != 'G'.code.toByte()) {
        return null
    }
    val width = readInt(bytes, IHDR_OFFSET)
    val height = readInt(bytes, IHDR_OFFSET + 4)
    return if (width > 0 && height > 0) width to height else null
}

private fun readInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

/** Nombre de niveaux d'un masque gris (8 bits) : la table de coloriage a cette taille. */
const val MASK_LEVELS = 256

/**
 * Table des [MASK_LEVELS] niveaux → couleur ARGB, alpha modulé **une fois pour toutes**.
 * Sans elle, chaque pixel payait `baseAlpha * level / 255` (une division) : sur 12 masques
 * de 105 000 pixels, c'était l'essentiel des 29 ms de coloriage mesurés. Le résultat est
 * **identique au pixel près** à l'ancien calcul (`alphaLut` = même formule).
 */
fun alphaLut(argb: Int, alpha: Int): IntArray {
    val rgb = argb and 0x00FFFFFF
    val baseAlpha = ((alpha.coerceIn(0, 255) * ((argb ushr 24) and 0xFF)) / 255).coerceIn(0, 255)
    return IntArray(MASK_LEVELS) { level ->
        if (level == 0) 0 else ((baseAlpha * level / 255) shl 24) or rgb
    }
}

/**
 * Colorie [mask] dans [lut] vers [out] : une lecture de niveau, une lecture de table,
 * une écriture — plus aucune division ni allocation par pixel. `out` doit contenir au
 * moins `mask.size` éléments ; les cases au-delà ne sont pas touchées (l'appelant peut
 * réutiliser un tampon plus grand d'une frame à l'autre).
 */
fun colorizeGrayInto(mask: GrayMask, lut: IntArray, out: IntArray) {
    require(out.size >= mask.size) { "tampon trop petit : ${out.size} < ${mask.size}" }
    val pixels = mask.pixels
    for (i in pixels.indices) out[i] = lut[pixels[i].toInt() and 0xFF]
}

/**
 * Colorie un masque gris : `argb` fournit la couleur, `alpha` l'opacité maximale,
 * le niveau du masque module l'alpha (0 = transparent).
 * Alloue le tableau de sortie : préférer [colorizeGrayInto] quand un tampon existe déjà.
 */
fun colorizeGray(mask: GrayMask, argb: Int, alpha: Int): IntArray {
    val out = IntArray(mask.size)
    colorizeGrayInto(mask, alphaLut(argb, alpha), out)
    return out
}

/**
 * Extrait un masque gris depuis des pixels ARGB (luminance, canal alpha ignoré).
 * **Hors du chemin du direct** : un masque PNG gris se décode directement en niveaux
 * (`decodeGrayMask`, ALPHA_8) ; cette fonction ne sert plus qu'aux images couleur.
 */
fun grayFromArgb(pixels: IntArray, width: Int, height: Int): GrayMask {
    val out = ByteArray(width * height)
    for (i in out.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return GrayMask(width, height, out)
}

/**
 * Décode un masque « bits » (format `M1` du backend : magic, largeur uint16, hauteur uint16,
 * puis zlib de lignes empaquetées 1 bit/pixel) en masque gris 0/255.
 * Trois fois plus léger qu'un PNG gris et **sans décodeur d'image** : un `Inflater` suffit.
 *
 * Le corps est décompressé **une seule fois**, puis il départage les deux leçons de
 * l'en-tête : celle dont `ceil(largeur/8) × hauteur` vaut exactement la longueur du corps.
 * Little-endian (le contrat) est essayé en premier, big-endian ne sert que de repli pour la
 * première salve du serveur. Si les deux leçons réclament la même longueur (tailles aux
 * octets symétriques, ex. 8×256 relu 2048×1), le contrat gagne : un backend ancien ne doit
 * jamais faire mal lire un masque courant.
 *
 * `null` — jamais d'exception — si le base64, l'en-tête, le flux zlib ou la longueur du
 * corps ne collent pas : l'appelant dessine alors la boîte au lieu d'un overlay mort.
 */
fun decodeBitsMask(maskB64: String): GrayMask? {
    val raw = base64Bytes(maskB64) ?: return null
    if (raw.size <= BITS_HEADER_SIZE) return null
    if (raw[0] != BITS_MAGIC_0 || raw[1] != BITS_MAGIC_1) return null
    val little = readUInt16(raw, 2, littleEndian = true) to readUInt16(raw, 4, littleEndian = true)
    val big = readUInt16(raw, 2, littleEndian = false) to readUInt16(raw, 4, littleEndian = false)
    val littleSize = bitsBodySize(little.first, little.second)
    val bigSize = bitsBodySize(big.first, big.second)
    // Un en-tête 65535×65535 réclame 512 Mo de corps : refusé ici, avant toute allocation.
    val hint = when {
        littleSize == 0 -> bigSize
        bigSize == 0 -> littleSize
        else -> minOf(littleSize, bigSize)
    }
    if (hint == 0) return null
    val body = inflateBitsBody(raw, hint) ?: return null
    return bitsMask(body, little.first, little.second) ?: bitsMask(body, big.first, big.second)
}

/**
 * Longueur que le corps d'un masque `width × height` doit avoir (`ceil(width/8) × height`),
 * ou `0` si cette taille est inutilisable : nulle, ou au-delà de [BITS_MAX_BODY_BYTES]
 * (8 Mo couvrent 67 millions de pixels, très au-dessus des 518 px du direct).
 * Les deux côtés viennent d'un uint16 : le produit tient sur un Int, sans débordement.
 */
private fun bitsBodySize(width: Int, height: Int): Int {
    if (width <= 0 || height <= 0) return 0
    val size = ((width + 7) / 8) * height
    return if (size in 1..BITS_MAX_BODY_BYTES) size else 0
}

/** Corps du format M1 pour une taille donnée : `null` si le corps ne fait pas cette taille. */
private fun bitsMask(body: ByteArray, width: Int, height: Int): GrayMask? {
    if (body.isEmpty()) return null
    val rowBytes = (width + 7) / 8
    if (bitsBodySize(width, height) != body.size) return null
    val out = ByteArray(width * height)
    for (y in 0 until height) unpackRow(body, y * rowBytes, out, y * width, width)
    return GrayMask(width, height, out)
}

/**
 * Dépaquette une ligne de bits en [width] niveaux 0/255, vers `out[to..]`.
 *
 * Les octets complets sont dépliés **huit pixels d'un coup** : un seul `body[...]`, huit
 * écritures indépendantes à décalage constant, donc plus d'index ni de décalage variable
 * par pixel dans le chemin chaud (mesuré sur 640×360 : décodage complet 350 → 169 µs).
 * Le dernier octet d'une ligne — partiel dès que la largeur n'est pas multiple de 8 — garde
 * la boucle pixel par pixel. Les deux chemins lisent les mêmes bits, dans le même ordre.
 */
private fun unpackRow(body: ByteArray, from: Int, out: ByteArray, to: Int, width: Int) {
    var x = 0
    while (x + 8 <= width) {
        val octet = body[from + (x shr 3)].toInt()
        out[to + x] = level(octet, 7)
        out[to + x + 1] = level(octet, 6)
        out[to + x + 2] = level(octet, 5)
        out[to + x + 3] = level(octet, 4)
        out[to + x + 4] = level(octet, 3)
        out[to + x + 5] = level(octet, 2)
        out[to + x + 6] = level(octet, 1)
        out[to + x + 7] = level(octet, 0)
        x += 8
    }
    while (x < width) {
        out[to + x] = level(body[from + (x shr 3)].toInt(), 7 - (x and 7))
        x++
    }
}

/**
 * Niveau du bit [shift] d'un octet M1 (bit de poids fort = premier pixel de la ligne).
 * Pas de `and 0xFF` : l'octet est signé, mais le `and 1` qui suit le décalage arithmétique
 * donne le bon bit dans les deux cas (le signe ne concerne que les bits au-dessus de 7).
 */
private fun level(octet: Int, shift: Int): Byte = if ((octet shr shift) and 1 == 1) 255.toByte() else 0

/** uint16 lu à [offset], little-endian (contrat) ou big-endian (repli première salve). */
private fun readUInt16(bytes: ByteArray, offset: Int, littleEndian: Boolean): Int {
    val a = bytes[offset].toInt() and 0xFF
    val b = bytes[offset + 1].toInt() and 0xFF
    return if (littleEndian) (b shl 8) or a else (a shl 8) or b
}

/**
 * Décompresse le corps M1 (`raw[BITS_HEADER_SIZE..]`) en un tableau de **sa longueur exacte**.
 * [hint] — la longueur réclamée par l'en-tête — sert de taille initiale : dans le cas
 * nominal le corps la remplit pile, donc aucune recopie. Ailleurs le tampon double, jusqu'à
 * [BITS_MAX_BODY_BYTES] ; l'allocation de départ reste bornée par [BITS_INITIAL_BYTES], pour
 * qu'un en-tête hostile ne fasse pas allouer des mégaoctets avant d'être rejeté.
 * Un corps **plus long** que [hint] n'est jamais tronqué en silence : on grandit, puis
 * [bitsMask] refuse la longueur — c'est le comportement de `decode_mask_bits_b64`.
 */
private fun inflateBitsBody(raw: ByteArray, hint: Int): ByteArray? {
    val inflater = java.util.zip.Inflater()
    return try {
        inflater.setInput(raw, BITS_HEADER_SIZE, raw.size - BITS_HEADER_SIZE)
        var body = ByteArray(minOf(hint, BITS_INITIAL_BYTES))
        var written = 0
        while (true) {
            if (written == body.size && !inflater.finished()) {
                if (body.size >= BITS_MAX_BODY_BYTES) return null // corps démesuré
                body = body.copyOf(minOf(body.size * 2, BITS_MAX_BODY_BYTES))
            }
            val n = inflater.inflate(body, written, body.size - written)
            if (n > 0) {
                written += n
                continue
            }
            if (inflater.finished()) break
            // ni entrée à venir ni dictionnaire : le flux zlib est incomplet
            if (inflater.needsInput() || inflater.needsDictionary()) return null
        }
        if (written == body.size) body else body.copyOf(written)
    } catch (e: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}
