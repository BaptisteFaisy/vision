package dev.sam3vision.core.picto

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Score minimal pour afficher le pictogramme (corrélation normalisée 0..1). */
const val MATCH_THRESHOLD = 0.72f

/** Échelles cherchées : l'objet peut être plus près ou plus loin que sur la photo. */
private val SCALES = floatArrayOf(0.7f, 1.0f, 1.4f)

/** Pas de balayage : 2 px à ~80 px de frame, la précision suffit à l'overlay. */
private const val STRIDE = 2

/** Sous cet écart-type, gabarit ou fenêtre sont unis : corrélation sans sens. */
private const val FLAT_STD = 0.5f

/**
 * Le gabarit d'une photo : son carré central (l'objet visé remplit le centre),
 * réduit à [TEMPLATE_EDGE] px. Pur et testé : le décodage JPEG vit dans `:app`.
 */
fun buildTemplate(photo: GrayImage): PictoTemplate {
    val side = max(8, min(photo.width, photo.height) / 2)
    val x0 = (photo.width - side) / 2
    val y0 = (photo.height - side) / 2
    val crop = IntArray(side * side) { i -> photo.gray[(y0 + i / side) * photo.width + x0 + i % side] }
    return PictoTemplate(resizeGray(crop, side, TEMPLATE_EDGE), TEMPLATE_EDGE)
}

/**
 * Meilleure occurrence du gabarit dans la frame, `null` si rien ne dépasse le
 * seuil. Corrélation croisée normalisée multi-échelle, moyennes et variances de
 * fenêtre en O(1) par images intégrales — ~1 M d'opérations à 80 px, 2 Hz sur
 * le téléphone sans dépendance native.
 */
fun matchTemplate(
    frame: GrayImage,
    template: PictoTemplate,
    pictoId: String,
    label: String,
    threshold: Float = MATCH_THRESHOLD,
): PictoHit? {
    if (isFlat(template.gray)) return null
    val sums = Integrals(frame)
    var best: PictoHit? = null
    for (scale in SCALES) {
        val edge = max(6, (template.edge * scale).toInt())
        if (edge >= frame.width || edge >= frame.height) continue
        val scaled = resizeGray(template.gray, template.edge, edge)
        val hit = scanScale(frame, sums, scaled, edge, pictoId, label)
        if (hit != null && (best == null || hit.score > best.score)) best = hit
    }
    return best?.takeIf { it.score >= threshold }
}

/** Vrai si le gabarit est uni : aucun motif à chercher, que des faux positifs. */
private fun isFlat(values: IntArray): Boolean = stdOf(values) < FLAT_STD

private fun stdOf(values: IntArray): Float {
    if (values.isEmpty()) return 0f
    val mean = values.average()
    var sum = 0.0
    for (v in values) sum += (v - mean) * (v - mean)
    return sqrt(sum / values.size).toFloat()
}

private fun centerFloats(values: IntArray, mean: Float): FloatArray =
    FloatArray(values.size) { i -> values[i] - mean }

/**
 * Sommes et sommes de carrés cumulées : moyenne et variance de n'importe quelle
 * fenêtre en O(1). `Long` : 80×80 px à 255 tiennent large, sans overfloat.
 */
private class Integrals(frame: GrayImage) {
    private val w = frame.width
    private val sum = LongArray((frame.width + 1) * (frame.height + 1))
    private val sumsq = LongArray((frame.width + 1) * (frame.height + 1))

    init {
        for (y in 0 until frame.height) {
            var row = 0L
            var rowSq = 0L
            for (x in 0 until frame.width) {
                val v = frame.gray[y * frame.width + x].toLong()
                row += v
                rowSq += v * v
                val i = (y + 1) * (frame.width + 1) + x + 1
                sum[i] = sum[i - frame.width - 1] + row
                sumsq[i] = sumsq[i - frame.width - 1] + rowSq
            }
        }
    }

    /** Moyenne et variance du rectangle `[x, x + rw[ × [y, y + rh[` ; `null` si plat. */
    fun window(x: Int, y: Int, rw: Int, rh: Int): Pair<Float, Float>? {
        val stride = w + 1
        val a = y * stride + x
        val b = y * stride + x + rw
        val c = (y + rh) * stride + x
        val d = (y + rh) * stride + x + rw
        val n = (rw * rh).toDouble()
        val mean = (sum[d] - sum[b] - sum[c] + sum[a]) / n
        val varr = (sumsq[d] - sumsq[b] - sumsq[c] + sumsq[a]) / n - mean * mean
        return if (varr < 1.0) null else Pair(mean.toFloat(), varr.toFloat())
    }
}

/** Balaye la frame à une échelle : meilleur rectangle, quel que soit son score. */
private fun scanScale(
    frame: GrayImage,
    sums: Integrals,
    scaled: IntArray,
    edge: Int,
    pictoId: String,
    label: String,
): PictoHit? {
    val templateStd = stdOf(scaled)
    if (templateStd < FLAT_STD) return null
    val centered = centerFloats(scaled, scaled.average().toFloat())
    var bestScore = -1f
    var bestX = 0
    var bestY = 0
    for (y in 0..frame.height - edge step STRIDE) {
        for (x in 0..frame.width - edge step STRIDE) {
            val (mean, variance) = sums.window(x, y, edge, edge) ?: continue
            val score = normalizedScore(frame, x, y, edge, centered, mean, templateStd, variance)
            if (score > bestScore) {
                bestScore = score
                bestX = x
                bestY = y
            }
        }
    }
    if (bestScore < 0f) return null
    return PictoHit(
        pictoId = pictoId,
        label = label,
        x = bestX.toFloat() / frame.width,
        y = bestY.toFloat() / frame.height,
        w = edge.toFloat() / frame.width,
        h = edge.toFloat() / frame.height,
        score = bestScore.coerceIn(0f, 1f),
    )
}

/**
 * Corrélation normalisée entre le gabarit centré et la fenêtre : covariance /
 * (σt·σw). Le gabarit étant centré, sa moyenne n'intervient plus.
 */
private fun normalizedScore(
    frame: GrayImage,
    x: Int,
    y: Int,
    edge: Int,
    centered: FloatArray,
    windowMean: Float,
    templateStd: Float,
    windowVar: Float,
): Float {
    var cross = 0.0
    for (dy in 0 until edge) {
        val row = (y + dy) * frame.width + x
        for (dx in 0 until edge) {
            cross += centered[dy * edge + dx] * (frame.gray[row + dx] - windowMean)
        }
    }
    val denom = templateStd * sqrt(windowVar) * edge * edge
    return if (denom < 1e-6f) -1f else (cross / denom).toFloat()
}

/** Rééchantillonnage bilinéaire carré → carré (gabarits et échelles). */
internal fun resizeGray(src: IntArray, srcEdge: Int, dstEdge: Int): IntArray {
    if (srcEdge == dstEdge) return src.copyOf()
    val dst = IntArray(dstEdge * dstEdge)
    val ratio = srcEdge.toFloat() / dstEdge
    for (y in 0 until dstEdge) {
        val sy = min((y * ratio).toInt(), srcEdge - 2)
        val fy = (y * ratio - sy).coerceIn(0f, 1f)
        for (x in 0 until dstEdge) {
            val sx = min((x * ratio).toInt(), srcEdge - 2)
            val fx = (x * ratio - sx).coerceIn(0f, 1f)
            val top = lerp(src[sy * srcEdge + sx], src[sy * srcEdge + sx + 1], fx)
            val bottom = lerp(src[(sy + 1) * srcEdge + sx], src[(sy + 1) * srcEdge + sx + 1], fx)
            dst[y * dstEdge + x] = lerp(top, bottom, fy)
        }
    }
    return dst
}

private fun lerp(a: Int, b: Int, f: Float): Int = (a + (b - a) * f).toInt()
