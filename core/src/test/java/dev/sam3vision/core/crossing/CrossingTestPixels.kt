package dev.sam3vision.core.crossing

/**
 * Pixels de test feu, partagés par les tests du paquet `crossing` : couleurs
 * ARGB, PPM, formes synthétiques et transforms du fuzz anti-inversion.
 * Top-level `internal` (un seul exemplaire, zéro duplication) : les fichiers
 * de test gardent leurs `private` quand ils en ont besoin (le membre
 * l'emporte sur le paquet en cas d'homonyme).
 */

/** Couleurs ARGB des pixels synthétiques des tests feu. */
internal const val RED = 0xFFFF0000.toInt()
internal const val GREEN = 0xFF00FF00.toInt()
internal const val CYAN_GREEN = 0xFF00FFAA.toInt()
internal const val BLACK = 0xFF000000.toInt()
internal const val GRAY = 0xFF808080.toInt()

/** Blanc opaque (poivre-et-sel du fuzz). */
internal const val WHITE = 0xFFFFFFFF.toInt()

internal val SPACES = byteArrayOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte())

/** Pixels + dimensions d'un PPM de test (`feuxfr` ou `feuxfr2`). */
internal fun pixelsOf(loader: ClassLoader?, dir: String, name: String): Triple<IntArray, Int, Int> {
    val bytes = loader?.getResourceAsStream("$dir/$name")?.readBytes()
        ?: error("ressource manquante : $dir/$name")
    return ppmPixels(bytes)
}

/** Pixels ARGB d'un PPM P6 brut (pas d'ImageIO sur le classpath des tests). */
internal fun ppmPixels(bytes: ByteArray): Triple<IntArray, Int, Int> {
    var pos = 0
    fun token(): String {
        while (pos < bytes.size && bytes[pos] in SPACES) pos++
        val start = pos
        while (pos < bytes.size && bytes[pos] !in SPACES) pos++
        return String(bytes, start, pos - start, Charsets.US_ASCII)
    }
    require(token() == "P6") { "PPM attendu" }
    val width = token().toInt()
    val height = token().toInt()
    require(token() == "255") { "PPM 255 attendu" }
    pos++
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val r = bytes[pos++].toInt() and 0xFF
        val g = bytes[pos++].toInt() and 0xFF
        val b = bytes[pos++].toInt() and 0xFF
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Triple(pixels, width, height)
}

/** Disque couleur au centre d'un cadre 20x20 noir. */
internal fun disc(cx: Int, cy: Int, radius: Int, color: Int = GREEN): IntArray {
    val pixels = IntArray(20 * 20) { BLACK }
    paintDisc(pixels, 20, 20, cx, cy, radius, color)
    return pixels
}

internal fun paintDisc(pixels: IntArray, w: Int, h: Int, cx: Int, cy: Int, radius: Int, color: Int) {
    for (y in 0 until h) {
        for (x in 0 until w) {
            val dx = x - cx
            val dy = y - cy
            if (dx * dx + dy * dy <= radius * radius) pixels[y * w + x] = color
        }
    }
}

/** Assombrit (facteur < 1, sans borne haute : ne pas utiliser pour éclaircir). */
internal fun dim(pixels: IntArray, factor: Float): IntArray =
    IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        if (pixel == BLACK) return@IntArray BLACK
        val r = (((pixel shr 16) and 0xFF) * factor).toInt()
        val g = (((pixel shr 8) and 0xFF) * factor).toInt()
        val b = ((pixel and 0xFF) * factor).toInt()
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

/** Applique [op] canal par canal (alpha préservé), résultat borné 0..255. */
internal fun xform(pixels: IntArray, op: (r: Int, g: Int, b: Int) -> Int): IntArray =
    IntArray(pixels.size) { i ->
        val p = pixels[i]
        (p and 0xFF000000.toInt()) or op(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)
    }

/** Compose un RGB borné. */
internal fun pack(r: Int, g: Int, b: Int): Int =
    (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

/** Gain par canal (éclaircit ou assombrit), borné. */
internal fun gain(pixels: IntArray, rf: Float, gf: Float, bf: Float): IntArray =
    xform(pixels) { r, g, b -> pack((r * rf).toInt(), (g * gf).toInt(), (b * bf).toInt()) }

/** Contraste autour du gris moyen. */
internal fun contrast(pixels: IntArray, factor: Float): IntArray =
    xform(pixels) { r, g, b ->
        pack(((r - 128) * factor + 128).toInt(), ((g - 128) * factor + 128).toInt(), ((b - 128) * factor + 128).toInt())
    }

/** Gamma via table (courbe d'exposition). */
internal fun gamma(pixels: IntArray, value: Double): IntArray {
    val lut = IntArray(256) { (255.0 * Math.pow(it / 255.0, value)).toInt().coerceIn(0, 255) }
    return xform(pixels) { r, g, b -> pack(lut[r], lut[g], lut[b]) }
}

/** Postérisation à [levels] niveaux par canal. */
internal fun posterize(pixels: IntArray, levels: Int): IntArray {
    val step = 256 / levels
    return xform(pixels) { r, g, b -> pack(r / step * step, g / step * step, b / step * step) }
}

/** Balance des blancs : gains par canal. */
internal fun whiteBalance(pixels: IntArray, rf: Float, gf: Float, bf: Float): IntArray =
    gain(pixels, rf, gf, bf)

/** Flou moyenneur 3x3 (bords : voisins existants). */
internal fun boxBlur(pixels: IntArray, w: Int, h: Int): IntArray =
    IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until w && ny in 0 until h) {
                val p = pixels[ny * w + nx]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                n++
            }
        }
        (0xFF shl 24) or pack(r / n, g / n, b / n)
    }

/** Moyenne par bloc [block] (compression destructrice simplifiée). */
internal fun blockAverage(pixels: IntArray, w: Int, h: Int, block: Int): IntArray {
    val out = pixels.copyOf()
    var by = 0
    while (by < h) {
        var bx = 0
        while (bx < w) {
            var r = 0
            var g = 0
            var b = 0
            var n = 0
            for (y in by until minOf(by + block, h)) for (x in bx until minOf(bx + block, w)) {
                val p = pixels[y * w + x]
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                n++
            }
            val mean = (0xFF shl 24) or pack(r / n, g / n, b / n)
            for (y in by until minOf(by + block, h)) for (x in bx until minOf(bx + block, w)) out[y * w + x] = mean
            bx += block
        }
        by += block
    }
    return out
}

/** Translation (remplissage noir). */
internal fun shift(pixels: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
    val out = IntArray(w * h) { BLACK }
    for (y in 0 until h) for (x in 0 until w) {
        val nx = x + dx
        val ny = y + dy
        if (nx in 0 until w && ny in 0 until h) out[ny * w + nx] = pixels[y * w + x]
    }
    return out
}

/** Mise à l'échelle (plus proche voisin). */
internal fun scaleNN(pixels: IntArray, w: Int, h: Int, num: Int, den: Int): Triple<IntArray, Int, Int> {
    val nw = maxOf(1, w * num / den)
    val nh = maxOf(1, h * num / den)
    val out = IntArray(nw * nh) { i -> pixels[(i / nw * den / num) * w + (i % nw * den / num)] }
    return Triple(out, nw, nh)
}

/** Occultation d'un demi-cadre noir placé par [seed] (déterministe). */
internal fun occlude(pixels: IntArray, w: Int, h: Int, seed: Int): IntArray {
    val rng = kotlin.random.Random(seed)
    val out = pixels.copyOf()
    val bw = maxOf(1, w / 2)
    val bh = maxOf(1, h / 2)
    val x0 = rng.nextInt(w - bw + 1)
    val y0 = rng.nextInt(h - bh + 1)
    for (y in y0 until y0 + bh) for (x in x0 until x0 + bw) out[y * w + x] = BLACK
    return out
}

/** Poivre-et-sel blanc : [fraction] des pixels touchés (graine fixée). */
internal fun saltPepper(pixels: IntArray, seed: Int, fraction: Float): IntArray {
    val out = pixels.copyOf()
    val count = (pixels.size * fraction).toInt()
    for (i in pixels.indices.shuffled(kotlin.random.Random(seed)).take(count)) out[i] = WHITE
    return out
}

/** Pixels verts parasites : [fraction] des pixels (graine fixée). */
internal fun greenNoise(pixels: IntArray, seed: Int, fraction: Float): IntArray {
    val out = pixels.copyOf()
    val count = (pixels.size * fraction).toInt()
    for (i in pixels.indices.shuffled(kotlin.random.Random(seed)).take(count)) out[i] = GREEN
    return out
}

/** Gigue uniforme ±[amp] par canal (graine fixée). */
internal fun uniformJitter(pixels: IntArray, seed: Int, amp: Int): IntArray {
    val rng = kotlin.random.Random(seed)
    return xform(pixels) { r, g, b ->
        pack(r + rng.nextInt(-amp, amp + 1), g + rng.nextInt(-amp, amp + 1), b + rng.nextInt(-amp, amp + 1))
    }
}
