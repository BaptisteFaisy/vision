package dev.sam3vision.core.predict

import dev.sam3vision.core.motion.MotionSample
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Verdict du vote fond vs objet (PISTE 1) : le véhicule bouge-t-il pour de
 * vrai, une fois l'ego-motion de la caméra soustraite ?
 */
enum class EgoVerdict {
    /** Le résidu (boîte − ego-motion projetée) est franc : mouvement propre. */
    MOVING,

    /** Résidu nul : la boîte suit le fond (pan) ou rien ne bouge. */
    STATIC,

    /** Preuve insuffisante : le doute ne parle jamais. */
    UNCERTAIN,
}

/**
 * Décision ego-compensée : [verdict], [residualDx]/[residualDy] le mouvement
 * propre (fractions d'image par seconde), [residual] sa norme, [confidence]
 * la confiance combinée (la plus faible des deux voies), [egoDx]/[egoDy]
 * l'ego-motion projetée à la boîte (mêmes unités, zéro sans fond prouvé).
 */
data class EgoDecision(
    val verdict: EgoVerdict,
    val residualDx: Float,
    val residualDy: Float,
    val residual: Float,
    val confidence: Float,
    val egoDx: Float,
    val egoDy: Float,
)

/**
 * Croissance résiduelle après décote du zoom du fond : [discounted] vrai =
 * le zoom explique la croissance, elle ne conclut plus l'approche.
 */
data class DiscountedGrowth(val residual: Float, val discounted: Boolean)

/**
 * Vote fond vs objet (PISTE 1) : qui bouge, le véhicule ou la caméra.
 *
 * Deux voies indépendantes votent — le déplacement de la boîte ([Shift] par
 * boîte, translation SAD) et le mouvement global du fond ([MotionSample],
 * translation + échelle) — et le résidu `boîte − ego-motion projetée` est le
 * mouvement propre du véhicule : franc, il roule ([EgoVerdict.MOVING]) ; nul,
 * il est fixe ([EgoVerdict.STATIC], que la caméra panne ou non) ; le reste est
 * doute ([EgoVerdict.UNCERTAIN]).
 *
 * L'ego-motion est projetée à la position de la boîte ([projectedEgo]) : sous
 * zoom, le fond ne défile pas pareil au centre et au bord — soustraire la
 * translation brute accuserait la boîte à tort. La croissance de boîte est
 * décotée séparément ([discountGrowth]) : quand le fond s'agrandit (caméra qui
 * avance, zoom), les boîtes grandissent aussi sans qu'aucun véhicule approche.
 *
 * Repli sans fond prouvé (estimateur muet, confiance basse) : l'ego-motion
 * vaut zéro et la boîte seule décide — le comportement historique, jamais un
 * silence inventé. Pur JVM, zéro allocation hors décision.
 */
object EgoCompensator {

    /** Dérive maximale d'un arrêt : 2 % de l'image par seconde (miroir du veto fond). */
    const val STILL_SPEED = 0.02f

    /** Au-delà (fraction d'image/s), le résidu est franc (miroir du veto fond). */
    const val MOTION_RATIO = 0.03f

    /** Confiance minimale d'une voie, reprise de `Shift.TRUST_MIN` : les deux voies se comparent. */
    const val TRUST_MIN = Shift.TRUST_MIN

    /** Marge sur le zoom attendu : la croissance reste décotée jusqu'à 50 % au-delà. */
    const val SCALE_TOLERANCE = 0.5f

    /** Plancher de bruit d'une croissance (1/s) : en dessous, c'est du bruit (miroir de l'approche). */
    const val GROWTH_EPS = 0.005f

    /** Sans fond prouvé, une croissance ≤ 2 %/s est de la gigue SAM, pas une approche (S1). */
    const val NO_FOND_RATE_EPS = 0.02f

    /** Sans fond prouvé, une aire qui prend ≤ 3 % par frame est de la gigue, pas une croissance (S1). */
    const val AREA_REL_EPS = 0.03f

    /**
     * Vote boîte vs fond : ([boxDxPx], [boxDyPx]) le déplacement de la boîte
     * (pixels de la grille [boxWidthPx]×[boxHeightPx], [boxConf] sa confiance),
     * [global] le mouvement du fond (`null` = estimateur muet, repli boîte
     * seule), [lumaWidth]×[lumaHeight] la grille du plan de luminance (unités
     * de [global]), [dtMs] le délai inter-frames (> 0), ([boxCx], [boxCy]) la
     * position de la boîte (fractions d'image, pour projeter le zoom).
     */
    fun decide(
        boxDxPx: Float,
        boxDyPx: Float,
        boxConf: Float,
        boxWidthPx: Int,
        boxHeightPx: Int,
        global: MotionSample?,
        lumaWidth: Int,
        lumaHeight: Int,
        dtMs: Long,
        boxCx: Float = 0.5f,
        boxCy: Float = 0.5f,
    ): EgoDecision {
        if (dtMs <= 0L || boxWidthPx <= 0 || boxHeightPx <= 0) return uncertain()
        if (!boxDxPx.isFinite() || !boxDyPx.isFinite() || !boxConf.isFinite()) return uncertain()
        if (!boxCx.isFinite() || !boxCy.isFinite()) return uncertain()
        val boxTrusted = boxConf >= TRUST_MIN
        // L'immobile retenu par le SAD (gain nul) n'est pas un doute : aucun
        // décalage n'explique mieux les données — déplacement nul recevable,
        // confiance plancher.
        val boxStatic = boxDxPx == 0f && boxDyPx == 0f
        if (!boxTrusted && !boxStatic) return uncertain()
        val dtSeconds = dtMs / 1000f
        val bx = boxDxPx / boxWidthPx / dtSeconds
        val by = boxDyPx / boxHeightPx / dtSeconds
        if (!bx.isFinite() || !by.isFinite()) return uncertain()
        val fondOk = usable(global, lumaWidth, lumaHeight)
        val ego = if (fondOk) projectedEgo(global!!, lumaWidth, lumaHeight, dtMs, boxCx, boxCy) else 0f to 0f
        val effectiveBox = if (boxTrusted) boxConf else TRUST_MIN
        val confidence = if (fondOk) min(effectiveBox, global!!.confidence) else effectiveBox
        val rx = bx - ego.first
        val ry = by - ego.second
        val residual = hypot(rx, ry)
        val verdict = when {
            residual > MOTION_RATIO -> EgoVerdict.MOVING
            residual <= STILL_SPEED -> EgoVerdict.STATIC
            else -> EgoVerdict.UNCERTAIN
        }
        return EgoDecision(verdict, rx, ry, residual, confidence.coerceIn(0f, 1f), ego.first, ego.second)
    }

    /**
     * Vote depuis un [Shift] par boîte (`null` = boîte injugeable, doute) :
     * même contrat que [decide], unités du [Shift] (pixels de la grille
     * [boxWidthPx]×[boxHeightPx]).
     */
    fun decide(
        shift: Shift?,
        boxWidthPx: Int,
        boxHeightPx: Int,
        global: MotionSample?,
        lumaWidth: Int,
        lumaHeight: Int,
        dtMs: Long,
        boxCx: Float = 0.5f,
        boxCy: Float = 0.5f,
    ): EgoDecision {
        if (shift == null) return uncertain()
        return decide(
            shift.dx.toFloat(), shift.dy.toFloat(), shift.confidence,
            boxWidthPx, boxHeightPx, global, lumaWidth, lumaHeight, dtMs, boxCx, boxCy,
        )
    }

    /**
     * Ego-motion projetée à la position ([atCx], [atCy]) (fractions d'image),
     * en fractions d'image par seconde : translation + bras du zoom
     * `(échelle − 1) × (position − centre)`. Au centre, le zoom ne déplace
     * rien ; hors bornes ou invalide, zéro (jamais d'exception).
     */
    fun projectedEgo(
        global: MotionSample,
        lumaWidth: Int,
        lumaHeight: Int,
        dtMs: Long,
        atCx: Float = 0.5f,
        atCy: Float = 0.5f,
    ): Pair<Float, Float> {
        if (dtMs <= 0L || lumaWidth <= 0 || lumaHeight <= 0) return 0f to 0f
        val cx = atCx.coerceIn(0f, 1f)
        val cy = atCy.coerceIn(0f, 1f)
        val dtSeconds = dtMs / 1000f
        val zoom = global.scale - 1f
        val ex = (global.dx + zoom * (cx * lumaWidth - lumaWidth / 2f)) / lumaWidth / dtSeconds
        val ey = (global.dy + zoom * (cy * lumaHeight - lumaHeight / 2f)) / lumaHeight / dtSeconds
        if (!ex.isFinite() || !ey.isFinite()) return 0f to 0f
        return ex to ey
    }

    /**
     * Décote la croissance linéaire de boîte [boxGrowthRate] (1/s, largeur —
     * voir [linearRateFromAreas] pour les aires) du zoom du fond : le zoom
     * attendu `|échelle − 1| / dt` est soustrait, le reste est le [DiscountedGrowth.residual].
     * Décotée ([DiscountedGrowth.discounted]) quand la croissance tient dans
     * le zoom attendu ([SCALE_TOLERANCE]) ou sous le bruit ([GROWTH_EPS]).
     * Sans fond prouvé : la croissance franche passe, la micro-croissance est
     * décotée ([NO_FOND_RATE_EPS], gigue SAM — la garée ne dit plus « Stop »).
     */
    fun discountGrowth(boxGrowthRate: Float, global: MotionSample?, dtMs: Long): DiscountedGrowth {
        if (!boxGrowthRate.isFinite() || boxGrowthRate <= 0f) return DiscountedGrowth(0f, false)
        if (global == null || dtMs <= 0L) return noFond(boxGrowthRate)
        if (!global.scale.isFinite() || !global.confidence.isFinite()) return noFond(boxGrowthRate)
        if (global.confidence < TRUST_MIN) return noFond(boxGrowthRate)
        val expected = abs(global.scale - 1f) / (dtMs / 1000f)
        if (!expected.isFinite()) return DiscountedGrowth(boxGrowthRate, false)
        val residual = max(0f, boxGrowthRate - expected)
        val discounted = boxGrowthRate <= expected * (1f + SCALE_TOLERANCE) || residual <= GROWTH_EPS
        return DiscountedGrowth(residual, discounted)
    }

    /**
     * Repli sans fond prouvé : la croissance franche passe (jamais de silence
     * inventé), la micro-croissance est décotée (gigue SAM, S1).
     */
    private fun noFond(boxGrowthRate: Float): DiscountedGrowth =
        if (boxGrowthRate <= NO_FOND_RATE_EPS) DiscountedGrowth(0f, true)
        else DiscountedGrowth(boxGrowthRate, false)

    /**
     * Croissance d'aire brute décotée du zoom : vrai quand [area] dépasse
     * [prevArea] sans que le zoom du fond ([global], [dtMs]) l'explique.
     * Invalide (temps, aires) ou sans fond prouvé : vrai comme avant (repli
     * historique, jamais de silence inventé).
     */
    fun areaGrowing(prevArea: Float, area: Float, dtMs: Long, global: MotionSample?): Boolean {
        if (area <= prevArea) return false
        if (prevArea > 0f && area <= prevArea * (1f + AREA_REL_EPS)) return false
        val rate = linearRateFromAreas(prevArea, area, dtMs) ?: return true
        return !discountGrowth(rate, global, dtMs).discounted
    }

    /**
     * Taux de croissance linéaire (1/s) entre deux aires : `sqrt(aire / aire
     * précédente) − 1` par seconde — une aire qui quadruple, c'est une largeur
     * qui double. `null` si injugeable (temps ou aires invalides).
     */
    fun linearRateFromAreas(prevArea: Float, area: Float, dtMs: Long): Float? {
        if (dtMs <= 0L || prevArea <= 0f) return null
        if (!prevArea.isFinite() || !area.isFinite()) return null
        val linear = sqrt(area / prevArea) - 1f
        if (!linear.isFinite()) return null
        return linear / (dtMs / 1000f)
    }

    /** Fond prouvé : présent, confiant, fini, sur une grille valide. */
    private fun usable(global: MotionSample?, lumaWidth: Int, lumaHeight: Int): Boolean {
        if (global == null || lumaWidth <= 0 || lumaHeight <= 0) return false
        if (!global.confidence.isFinite() || global.confidence < TRUST_MIN) return false
        return global.dx.isFinite() && global.dy.isFinite() && global.scale.isFinite()
    }

    /** Doute : vitesses et confiance à zéro, jamais d'arrêt ni de mouvement inventé. */
    private fun uncertain(): EgoDecision = EgoDecision(EgoVerdict.UNCERTAIN, 0f, 0f, 0f, 0f, 0f, 0f)
}
