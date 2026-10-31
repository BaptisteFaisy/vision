package dev.sam3vision.core.iseedesign

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
/**
 * La dissolution de la carte caméra dans la grande bulle, comme dans la vidéo :
 * la photo se **désagrège en damier** (chaque cellule quitte la carte à son
 * instant [DissolvePath.detach]), les pixels partent en **gerbe** au-dessus de
 * la carte, puis convergent en arc dans la bulle qui **grandit** de son ancre
 * (sous la carte à droite) vers le centre ; chaque pixel atterri y peint sa tache
 * ([PixelStage]→[worldAlpha]), jusqu'à ce que le disque soit un « monde » aux
 * couleurs de la photo.
 *
 * Le plan est pur et déterministe : le même index, au même instant, donne
 * toujours le même trajet ; l'app n'ajoute que le pinceau (Canvas).
 */

/** Durée totale de la dissolution, ms — la vidéo tient ~2,6 s sur 30 i/s. */
const val DISSOLVE_MS = 2_600

/** Grille de dissolution : cellules carrées (26 × 16 sur une carte ≈ 84 % × 22 %). */
const val DISSOLVE_COLS = 26
const val DISSOLVE_ROWS = 16

/** Quantité de cellules posées sur la carte (cellules carrées). */
const val CARD_COLS = DISSOLVE_COLS
const val CARD_ROWS = DISSOLVE_ROWS

/**
 * Géométrie de la scène, en px de l'écran : la grande bulle et sa carte caméra.
 * [morph] anime la bulle : 0 = ancre de capture (sous la carte, à droite, petite),
 * 1 = pose finale (centrée, pleine). Les satellites suivent le même centre.
 */
data class SceneGeom(
    val width: Float,
    val height: Float,
    val bubbleCx: Float,
    val bubbleCy: Float,
    val bubbleR: Float,
    val satelliteR: Float,
    val cardCx: Float,
    val cardCy: Float,
    val cardW: Float,
    val cardH: Float,
)

/** Rayon d'un satellite, en parts du rayon de la grande bulle (la vidéo ≈ 0,16). */
const val SATELLITE_SHARE = 0.155f

fun iSeeGeometry(width: Float, height: Float, morph: Float = 1f): SceneGeom {
    require(width > 0f && height > 0f) { "screen" }
    val e = morph.coerceIn(0f, 1f)
    val r = lerp(width * 0.205f, width * 0.335f, e)
    val cx = lerp(width * 0.72f, width * 0.50f, e)
    val cy = lerp(height * 0.44f, height * 0.38f, e)
    return SceneGeom(
        width = width,
        height = height,
        bubbleCx = cx,
        bubbleCy = cy,
        bubbleR = r,
        satelliteR = r * SATELLITE_SHARE,
        cardCx = width * 0.5f,
        cardCy = height * 0.198f,
        cardW = width * 0.84f,
        cardH = height * 0.22f,
    )
}

/** Un trajet de pixel : borne de la carte → gerbe → porte de la bulle → atterrissage. */
data class DissolvePath(
    /** Centre de départ dans la carte, en parts d'écran. */
    val cellX: Float,
    val cellY: Float,
    /** Point de contrôle du burst (au-dessus de la carte), en parts d'écran. */
    val burstX: Float,
    val burstY: Float,
    /** Point d'entrée dans la bulle, en parts du centre-bulle (rayons). */
    val gateU: Float,
    val gateV: Float,
    /** Atterrissage dans le disque, en parts du centre-bulle (rayons). */
    val endU: Float,
    val endV: Float,
    /** Instant où la cellule quitte la carte, 0…0,62. */
    val detach: Float,
    /** Instant où le pixel touche la bulle, detach…1. */
    val arrive: Float,
    /** Graine 0…1 du pixel : taille, balancement, éclat à l'atterrissage. */
    val seed: Float,
)

/** Le plan complet : un trajet par cellule, index = ligne × colonnes + colonne. */
class DissolvePlan internal constructor(val paths: Array<DissolvePath>) {
    fun size(): Int = paths.size
}

/**
 * Construit le plan pour [DISSOLVE_COLS]×[DISSOLVE_ROWS] cellules, tiré de la
 * géométrie de la scène. La vague de dessaisissement part du coin haut-gauche
 * de la carte (comme la vidéo) ; la gerbe éclate au-dessus, puis chaque pixel
 * plonge dans le disque.
 */
fun dissolvePlan(geom: SceneGeom, cols: Int = DISSOLVE_COLS, rows: Int = DISSOLVE_ROWS): DissolvePlan {
    require(cols > 0 && rows > 0) { "grille" }
    // Direction carte → bulle : la porte se pose sur le bord du côté de la carte.
    val dx = geom.cardCx - geom.bubbleCx
    val dy = geom.cardCy - geom.bubbleCy
    val norm = (sqrt(dx * dx + dy * dy)).coerceAtLeast(1e-3f)
    val ux = dx / norm
    val uy = dy / norm
    val waveX = geom.cardCx - geom.cardW * 0.42f // coin haut-gauche de la carte
    val waveY = geom.cardCy - geom.cardH * 0.46f
    val diag = (geom.cardW * geom.cardW + geom.cardH * geom.cardH)
    val paths = Array(cols * rows) { i ->
        val col = i % cols
        val row = i / cols
        val cx = geom.cardCx - geom.cardW * 0.5f + (col + 0.5f) / cols * geom.cardW
        val cy = geom.cardCy - geom.cardH * 0.5f + (row + 0.5f) / rows * geom.cardH
        // Vague de dessaisissement : distance au coin haut-gauche, plus la graine.
        var ddx = cx - waveX
        var ddy = cy - waveY
        val dist = sqrt(ddx * ddx + ddy * ddy) / diag
        val detach = (dist * 1.15f + rnd(i, 1) * 0.20f).coerceIn(0f, 0.55f)
        val flight = 0.30f + rnd(i, 2) * 0.20f
        val arrive = (detach + flight).coerceAtMost(0.99f)
        // Burst : au-dessus de la carte, poussé latéralement hors du centre.
        val burstX = cx + (cx - geom.cardCx) * 1.8f + (rnd(i, 3) - 0.5f) * 0.6f * geom.cardW
        val burstY = geom.cardCy - geom.cardH * (0.15f + rnd(i, 4) * 0.45f)
        // Porte d'entrée : sur le bord de la bulle côté carte, balayée le long du bord.
        val sweep = (rnd(i, 5) - 0.5f) * 1.5f
        val gateU = ux * 0.62f - uy * sweep
        val gateV = uy * 0.62f + ux * sweep
        // Atterrissage : la carte se remappe sur le disque comme sur un globe —
        // la colonne donne la hauteur, la ligne se repartit dans l'accord du disque.
        val uu = ((col + 0.5f) / cols * 2f - 1f) * 0.92f
        val chord = sqrt((0.94f * 0.94f - uu * uu).coerceAtLeast(0f))
        val endU = uu
        val endV = ((row + 0.5f) / rows * 2f - 1f) * chord
        DissolvePath(
            cellX = cx, cellY = cy,
            burstX = burstX, burstY = burstY,
            gateU = gateU, gateV = gateV,
            endU = endU, endV = endV,
            detach = detach, arrive = arrive,
            seed = rnd(i, 8),
        )    }
    return DissolvePlan(paths)
}

/** Position d'un pixel en vol, ou null : pas encore détaché, ou déjà posé. */
data class PixelStage(val x: Float, val y: Float, /** Taille 0…2 (1 = cellule pleine). */
val size: Float, val alpha: Float, val seed: Float)

fun pixelStage(plan: DissolvePath, geom: SceneGeom, t: Float): PixelStage? {
    if (t <= plan.detach || t >= plan.arrive) return null
    val span = (plan.arrive - plan.detach).coerceAtLeast(1e-4f)
    val q = smoothstep(((t - plan.detach) / span).coerceIn(0f, 1f))
    val endX = geom.bubbleCx + plan.endU * geom.bubbleR
    val endY = geom.bubbleCy + plan.endV * geom.bubbleR
    val x = cubic(plan.cellX, plan.burstX, geomBurstGateX(plan, geom), endX, q)
    val y = cubic(plan.cellY, plan.burstY, geomBurstGateY(plan, geom), endY, q)
    // Taille : pleine au départ, le pixel s'affine à l'approche du verre.
    val base = 0.85f + plan.seed * 0.3f
    val size = lerp(base, base * 0.42f, q)
    val alpha = if (q < 0.14f) q / 0.14f else 1f
    return PixelStage(x = x, y = y, size = size, alpha = alpha, seed = plan.seed)
}

private fun geomBurstGateX(plan: DissolvePath, geom: SceneGeom): Float =
    geom.bubbleCx + plan.gateU * geom.bubbleR * 1.35f

private fun geomBurstGateY(plan: DissolvePath, geom: SceneGeom): Float =
    geom.bubbleCy + plan.gateV * geom.bubbleR * 1.35f

/** Point où le pixel atterrit, en parts du centre-bulle (rayons), pour la bulle. */
fun worldSpot(plan: DissolvePath, geom: SceneGeom): Pair<Float, Float> =
    (geom.bubbleCx + plan.endU * geom.bubbleR) to (geom.bubbleCy + plan.endV * geom.bubbleR)

/** Alpha de la tache de la bulle posée par le pixel : pop d'atterrissage 0→1. */
fun worldAlpha(plan: DissolvePath, t: Float): Float {
    val span = 0.10f
    return ((t - plan.arrive) / span).coerceIn(0f, 1f)
}

/** Éclat à l'atterrissage : la tache s'allume claire puis s'assoit dans le monde. */
fun worldGlint(plan: DissolvePath, t: Float): Float =
    (1f - ((t - plan.arrive) / 0.22f)).coerceIn(0f, 1f)

/** Moyenne pondérée, pur. */
fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Accélération douce aux deux bouts, pour les pixels en balancier. */
fun smoothstep(t: Float): Float {
    val c = t.coerceIn(0f, 1f)
    return c * c * (3f - 2f * c)
}

/** Sortie cubique : rapide au départ, frein à l'arrivée (utilisé par l'app). */
fun easeOutCubic(t: Float): Float {
    val c = (1f - t.coerceIn(0f, 1f))
    return 1f - c * c * c
}

/** Reprise du morph de la bulle depuis l'instant t du dissolve (fin ≈ 0,62). */
fun bubbleMorph(t: Float): Float = easeOutCubic((t / 0.62f).coerceIn(0f, 1f))

/** Cubique de bézier coordonnée x (le y appelle la même avec ses bornes). */
fun cubic(x0: Float, x1: Float, x2: Float, x3: Float, t: Float): Float {
    val u = 1f - t
    return u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
}

/** Graine déterministe par paire (index, sel) → 0…1, sans tableau alloué. */
internal fun rnd(index: Int, salt: Int): Float {
    var h = (index + 1).toLong() * -7046029254386353131L xor salt.toLong() * -4503599627370497L
    h = h xor (h ushr 30) * -49064778989728563L
    h = h xor (h ushr 27) * -49064778989728563L
    h = h xor (h ushr 31)
    return ((h ushr 11) and 0x1FFFFFL).toFloat() / 2097152f
}

/** Éclaire une couleur ARGB de k∈0…1 (pixels qui s'allument en atterrissant). */
fun glint(argb: Int, k: Float): Int {
    if (k <= 0f) return argb
    val w = (k * 0.5f).coerceIn(0f, 0.5f)
    val a = (argb ushr 24) and 0xFF
    val r = (argb ushr 16) and 0xFF
    val g = (argb ushr 8) and 0xFF
    val b = argb and 0xFF
    fun chan(c: Int): Int = c + ((255 - c).toFloat() * w).toInt()
    return (a shl 24) or (chan(r) shl 16) or (chan(g) shl 8) or chan(b)
}

/** Un satellite : position relative (parts du rayon-bulle) stable determinée. */
fun satelliteAlpha(morph: Float): Float = morph.coerceIn(0f, 1f)

/** Longueur du tick, en parts du rayon : alternance pseudo-aléatoire stable. */
fun tickLength(index: Int, phase: Float): Float {
    val base = 0.17f + abs(sin(index * 2.39996323f)) * 0.12f
    val wave = 1f + 0.30f * sin(index * 0.7f + phase * 2f * PI.toFloat())
    return base * wave
}
