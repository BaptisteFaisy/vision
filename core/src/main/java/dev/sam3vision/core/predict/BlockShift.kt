package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import kotlin.math.abs

/** Déplacement entier estimé entre deux images, avec sa confiance (0 = aucun gain). */
data class Shift(val dx: Int, val dy: Int, val confidence: Float) {

    /** Vrai si le décalage retenu fait nettement mieux que l'immobile. */
    val trustworthy: Boolean get() = confidence >= TRUST_MIN

    companion object {
        /** En dessous, on ignore le résultat : image plate, floue, ou objet sorti du champ. */
        const val TRUST_MIN = 0.12f
        val Zero = Shift(0, 0, 0f)
    }
}

/**
 * Estimation de translation par **block-matching SAD** sur une grille de points pris
 * dans la boîte — l'alternative légère à Lucas-Kanade (pas d'OpenCV, pas de gradient).
 *
 * On échantillonne la zone tous les [PROBE_STEP_PX] px (une boîte de 120×90 → ~470
 * points) et on teste les 13 × 13 = 169 décalages de −[DEFAULT_SEARCH_PX] à
 * +[DEFAULT_SEARCH_PX] px, en partant du centre (l'immobile gagne les égalités).
 * Coût ≈ 79 k comparaisons d'octets par objet et par frame, soit ~19 M ops/s pour
 * 8 objets à 30 fps : 1 à 3 % d'un cœur moyen, à comparer aux 10-20 ms de décodage
 * JPEG de la même frame.
 *
 * Pourquoi une recherche pleine (pas de coarse-to-fine) : sur une texture riche, le
 * coût n'est pas convexe — un pas de 2 px peut retenir un minimum secondaire à plus de
 * 2 px du vrai, et un raffinement de ±1 ne le rattrape jamais. Sur des images réelles
 * (structure lisse) les deux marchent ; la recherche pleine est la seule sûre.
 *
 * Limite assumée : translation seule. L'échelle et la rotation du contour restent à la
 * vitesse de [MotionModel], recalée à chaque réponse serveur.
 */
fun estimateShift(
    prev: GrayMask,
    cur: GrayMask,
    box: Box,
    searchPx: Int = DEFAULT_SEARCH_PX,
    stepPx: Int = PROBE_STEP_PX,
): Shift? {
    if (prev.width != cur.width || prev.height != cur.height) return null
    val area = sampleRect(box, prev.width, prev.height, stepPx, searchPx) ?: return null
    val zero = sad(prev, cur, area, 0, 0, stepPx) ?: return null
    val offsets = centeredOffsets(searchPx)
    val best = bestIn(prev, cur, area, stepPx, offsets, offsets) ?: return null
    return Shift(best.first.dx, best.first.dy, confidence(zero, best.second))
}

/** Zone échantillonnée, entière, amputée de la marge de recherche. */
internal data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** `null` si la boîte visible est trop petite pour donner un avis statistique. */
internal fun sampleRect(box: Box, width: Int, height: Int, stepPx: Int, marginPx: Int): Rect? {
    val left = box.x1.toInt().coerceIn(0, maxOf(0, width - 1))
    val top = box.y1.toInt().coerceIn(0, maxOf(0, height - 1))
    val right = box.x2.toInt().coerceIn(left + 1, width)
    val bottom = box.y2.toInt().coerceIn(top + 1, height)
    val rect = Rect(
        left + marginPx,
        top + marginPx,
        maxOf(left + marginPx + 1, right - marginPx),
        maxOf(top + marginPx + 1, bottom - marginPx),
    )
    return if ((rect.width / stepPx) * (rect.height / stepPx) < MIN_SAMPLES) null else rect
}

/** Décalages dans l'ordre de préférence : 0 d'abord, puis ±1, ±2… */
internal fun centeredOffsets(limit: Int): List<Int> {
    val out = mutableListOf(0)
    var v = 1
    while (v <= limit) {
        out += v
        out += -v
        v += 1
    }
    return out
}

/** Meilleur `(décalage, coût)` ; les égalités profitent au décalage essayé en premier. */
internal fun bestIn(
    prev: GrayMask,
    cur: GrayMask,
    area: Rect,
    stepPx: Int,
    xs: List<Int>,
    ys: List<Int>,
    within: ((x: Int, y: Int) -> Boolean)? = null,
): Pair<Shift, Long>? {
    var best: Pair<Shift, Long>? = null
    for (dy in ys) {
        for (dx in xs) {
            val cost = sad(prev, cur, area, dx, dy, stepPx, within) ?: continue
            if (best == null || cost < best!!.second) best = Shift(dx, dy, 0f) to cost
        }
    }
    return best
}

/**
 * Somme des écarts absolus normalisée par le nombre de points valides (millièmes, pour
 * rester en entier). `null` si trop peu de points tombent dans l'image décalée.
 * [within] restreint les points échantillonnés (`null` = tout le rectangle) : c'est la
 * variante masquée ([estimateMaskedShift]) qui n'y mesure que les pixels de l'objet.
 */
internal fun sad(
    prev: GrayMask,
    cur: GrayMask,
    area: Rect,
    dx: Int,
    dy: Int,
    stepPx: Int,
    within: ((x: Int, y: Int) -> Boolean)? = null,
): Long? {
    var sum = 0L
    var hits = 0
    var y = area.top
    while (y < area.bottom) {
        var x = area.left
        while (x < area.right) {
            if (within != null && !within(x, y)) {
                x += stepPx
                continue
            }
            val cx = x + dx
            val cy = y + dy
            if (cx in 0 until cur.width && cy in 0 until cur.height) {
                sum += abs(prev.levelAt(x, y) - cur.levelAt(cx, cy))
                hits += 1
            }
            x += stepPx
        }
        y += stepPx
    }
    return if (hits < MIN_SAMPLES) null else sum * 1000L / hits
}

/** Gain relatif du meilleur décalage par rapport à l'immobile, dans [0, 1]. */
internal fun confidence(zeroCost: Long, bestCost: Long): Float {
    if (zeroCost <= 0L) return 0f
    return ((zeroCost - bestCost).toFloat() / zeroCost).coerceIn(0f, 1f)
}

/** Pas d'échantillonnage : 4 px suffit sur une boîte de 100 px sans perdre le signe. */
const val PROBE_STEP_PX = 4

/** Amplitude de recherche : ±6 px à 512 px de large, ~1 % de l'image par frame. */
const val DEFAULT_SEARCH_PX = 6

internal const val MIN_SAMPLES = 24
