package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec
import kotlin.math.abs

/**
 * Cap marché conseillé quand le couloir droit est bouché : [headingX] est le cap
 * normalisé qui passe au plus près de la visée en évitant les corps, [side] son
 * côté, [clearanceM] l'écart latéral mini au corps (ou au bord mesuré) le long du
 * cap. [fits] faux = aucun cap ne passe (bouché d'un bord à l'autre) : [headingX]
 * vaut alors la visée et [side] son côté — inutilisés, l'appelant garde son repli
 * (`freerSide` côté [Guidance]).
 */
data class PeoplePath(
    val headingX: Float,
    val side: GuidanceSide,
    val clearanceM: Float,
    val fits: Boolean,
) {
    /**
     * Vrai si le cap s'écarte vraiment de tout droit : un cap resté sur l'axe ne
     * contourne rien, son côté n'est qu'une convention — l'appelant garde son repli.
     */
    val detour: Boolean get() = fits && abs(headingX - 0.5f) > EDGE_EPS

    companion object {

        /**
         * Écart mini au corps d'une personne (m) : on passe à 1 m ou plus
         * (ordre user du 02/11 — 0,8 m avant, on frôlait les gens).
         */
        const val MIN_CLEARANCE_M = 1.0f

        /**
         * Demi-largeur de corps (m) quand la boîte est inconnue : les épaules
         * (≈ 0,45 m) plutôt que rien — même prudence que le trait de [Guidance].
         */
        const val BODY_HALF_WIDTH_M = 0.25f

        /** Cap imposé dans `[EDGE_X, 1 - EDGE_X]` : on ne vise jamais le bord de l'image. */
        const val EDGE_X = 0.02f

        /**
         * Marges essayées autour d'un obstacle fixe (m), la plus large d'abord :
         * 1 m comme aux personnes quand ça passe, 0,6 m sur un trottoir serré,
         * le corps seul en dernier recours — on ne frôle que si rien d'autre ne
         * passe. Sans cet ordre, un poteau ne gardait que 25 cm : l'épaule.
         */
        private val STATIC_MARGINS_M = floatArrayOf(1f, 0.6f, Guidance.RAY_HALF_WIDTH_M)

        /**
         * Deux sorties à moins de cet écart de cap sont à égalité : obstacle plein
         * axe, on passe à droite, sans que le bruit du cadre change le côté. Une
         * règle sans mémoire, pas une tenue (ordre user du 03/11).
         */
        internal const val AXIS_TIE_X = 0.1f

        /** Écart au corps (m) sous lequel deux sorties à égalité se valent. */
        internal const val CLEARANCE_TIE_M = 0.05f

        /**
         * Cap qui passe au plus près de [aimX] ([Guidance.RANGE_METERS] et champ
         * [hfovDeg] comme le couloir), à la marge la plus large qui passe
         * ([STATIC_MARGINS_M]) : `null` si rien n'est à portée, si un obstacle à
         * portée a une géométrie incomplète (ni distance ni bords, ou sans centre
         * fini — [ClearPath.depthOf] situe un objet aux bords connus), ou si le
         * champ ou la visée sont infaisables
         * (`coerceIn` propage NaN : une projection NaN rendrait tout cap
         * « libre » — repli : l'appelant garde son ancien comportement).
         * Une passe O(n), un seul `tan`, tampons pré-alloués, pur et testé en JVM.
         */
        fun plan(
            labels: List<PlacedLabel>,
            rangeMeters: Float = Guidance.RANGE_METERS,
            hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
            aimX: Float = 0.5f,
        ): PeoplePath? {
            val halfTangent = LensSpec.halfTangent(hfovDeg)
            if (!halfTangent.isFinite() || halfTangent <= 0f) return null
            if (!aimX.isFinite()) return null
            val bands = ObstacleBands(labels.size)
            val aim = aimX.coerceIn(EDGE_X, 1f - EDGE_X)
            for (margin in STATIC_MARGINS_M) {
                if (!bands.collect(labels, rangeMeters, halfTangent, margin)) return null
                val heading = bands.select(aim) ?: continue
                return PeoplePath(heading, sideOf(heading), bands.clearance(heading), true)
            }
            return PeoplePath(aim, sideOf(aim), bands.clearance(aim), false)
        }

        /** Côté du cap : à gauche sous l'axe, à droite dessus (pile au centre ⇒ droite). */
        private fun sideOf(headingX: Float): GuidanceSide =
            if (headingX < 0.5f) GuidanceSide.Left else GuidanceSide.Right

        /**
         * Vrai si marcher côté [side] garantit 1 m à tout corps : aucun
         * corps à portée avec un écart latéral < corps + 1 m
         * ([MIN_CLEARANCE_M]), aucun corps non mesuré de ce côté
         * (prudence : l'inconnu bloque le côté — un corps plein centre
         * non mesuré bloque les deux). Les obstacles non humains ne comptent
         * pas (le couloir les contourne déjà à 25 cm). Pur, testé en JVM.
         */
        fun clearsSide(
            labels: List<PlacedLabel>,
            side: GuidanceSide,
            rangeMeters: Float = Guidance.RANGE_METERS,
            hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        ): Boolean {
            val halfTangent = LensSpec.halfTangent(hfovDeg)
            if (!halfTangent.isFinite() || halfTangent <= 0f) return false
            for (label in labels) {
                if (!PersonLabels.isBody(label.label)) continue
                if (!onSide(label.centerX, side)) continue
                val meters = label.meters
                if (meters == null || !meters.isFinite() || meters <= 0f) return false
                if (meters > rangeMeters) continue
                val perX = lateralPerX(meters, halfTangent)
                val lateral = abs(label.centerX - 0.5f) * perX
                if (lateral < bodyHalfM(label, perX) + MIN_CLEARANCE_M) return false
            }
            return true
        }

        /**
         * Vrai si marcher dans l'axe garantit 1 m à tout corps : aucun
         * corps à portée avec un écart latéral à l'axe < corps + 1 m,
         * aucun corps non mesuré dans la bande centrale (miroir du
         * couloir : hors bande, l'inconnu passe au large).
         */
        fun clearsAhead(
            labels: List<PlacedLabel>,
            rangeMeters: Float = Guidance.RANGE_METERS,
            hfovDeg: Float = LensSpec.DEFAULT_H_FOV_DEG,
        ): Boolean {
            val halfTangent = LensSpec.halfTangent(hfovDeg)
            if (!halfTangent.isFinite() || halfTangent <= 0f) return false
            for (label in labels) {
                if (!PersonLabels.isBody(label.label)) continue
                val meters = label.meters
                if (meters == null || !meters.isFinite() || meters <= 0f) {
                    if (!label.centerX.isFinite() || label.centerX in PositionText.SIDE_EDGE..1f - PositionText.SIDE_EDGE) return false
                    continue
                }
                if (meters > rangeMeters) continue
                val perX = lateralPerX(meters, halfTangent)
                if (abs(label.centerX - 0.5f) * perX < bodyHalfM(label, perX) + MIN_CLEARANCE_M) return false
            }
            return true
        }

        /** [centerX] côté [side] (le centre pile bloque les deux côtés, prudence). */
        private fun onSide(centerX: Float, side: GuidanceSide): Boolean {
            if (!centerX.isFinite()) return true
            return if (side == GuidanceSide.Left) centerX <= 0.5f else centerX >= 0.5f
        }
    }
}

/**
 * Demi-largeur de boîte en mètres (`null` = bords inconnus), partagée
 * bandes ([ObstacleBands]) et garantie 1 m ([PeoplePath.clearsSide]).
 */
internal fun halfBoxM(label: PlacedLabel, lateralPerX: Float): Float? {
    val x0 = label.x0
    val x1 = label.x1
    if (x0 == null || x1 == null || !x0.isFinite() || !x1.isFinite()) return null
    val width = abs(x1 - x0)
    if (width <= 0f) return null
    return width * 0.5f * lateralPerX
}

/**
 * Demi-corps en mètres : boîte mesurée, sinon épaules par défaut pour un
 * corps, zéro pour le reste (un poteau ne déborde pas de sa mesure).
 */
internal fun bodyHalfM(label: PlacedLabel, lateralPerX: Float): Float {
    val halfBox = halfBoxM(label, lateralPerX)
    return if (PersonLabels.isBody(label.label)) halfBox ?: PeoplePath.BODY_HALF_WIDTH_M else halfBox ?: 0f
}

/** Frontière de bande : en deçà, le cap est hors bande (le bord lui-même passe). */
private const val EDGE_EPS = 1e-4f

/**
 * Largeur de champ à [depth] mètres : ce que vaut tout l'axe x en mètres —
 * même projection que le trait du couloir (`halfTangent` borné caméra).
 */
internal fun lateralPerX(depth: Float, halfTangent: Float): Float = 2f * depth * halfTangent

/** Bandes interdites en cap et géométrie pour l'écart : tampons pré-alloués, une passe. */
private class ObstacleBands(size: Int) {
    private val lo = FloatArray(size)
    private val hi = FloatArray(size)
    private val centers = FloatArray(size)
    private val bodies = FloatArray(size)
    private val widths = FloatArray(size)
    private var count = 0

    /**
     * Remplit les bandes en une passe, [staticMargin] autour des obstacles fixes :
     * faux si un obstacle à portée a une géométrie incomplète, ou si rien n'est à
     * portée (même règle que le couloir : distance absente = à portée, par
     * prudence). Un mur longé ([ClearPath.isRail]) ferme tout son côté.
     */
    fun collect(labels: List<PlacedLabel>, rangeMeters: Float, halfTangent: Float, staticMargin: Float): Boolean {
        count = 0
        var index = 0
        while (index < labels.size) {
            val label = labels[index]
            index++
            val meters = label.meters
            if (meters != null && meters.isFinite() && meters > rangeMeters) continue
            if (ClearPath.isRail(label)) {
                addRail(label, rangeMeters, halfTangent, staticMargin)
                continue
            }
            val depth = ClearPath.depthOf(label, rangeMeters) ?: return false
            val center = label.centerX
            if (!center.isFinite()) return false
            val lateralPerX = lateralPerX(depth, halfTangent)
            val person = PersonLabels.isBody(label.label)
            val body = bodyHalfM(label, lateralPerX)
            val margin = if (person) PeoplePath.MIN_CLEARANCE_M else staticMargin
            val half = (body + margin) / lateralPerX
            if (!add(center - half, center + half, center, body, lateralPerX)) return false
        }
        return count > 0
    }

    /** Ajoute une bande, faux si elle n'est pas finie (une projection NaN libérerait tout). */
    private fun add(start: Float, end: Float, center: Float, body: Float, perX: Float): Boolean {
        if (!start.isFinite() || !end.isFinite()) return false
        lo[count] = start
        hi[count] = end
        centers[count] = center
        bodies[count] = body
        widths[count] = perX
        count++
        return true
    }

    /**
     * Bande d'un mur longé : tout son côté, jusqu'au cap qui garde l'écart voulu
     * à portée. Son bord intérieur ne le situe pas (point de fuite), sa distance
     * latérale si ; sans mesure, on le suppose à [ClearPath.UNSEEN_DEPTH_M].
     */
    private fun addRail(label: PlacedLabel, rangeMeters: Float, halfTangent: Float, staticMargin: Float) {
        val lateral = ClearPath.railLateralM(label, halfTangent)
            ?: (ClearPath.UNSEEN_DEPTH_M * halfTangent)
        val perX = lateralPerX(rangeMeters, halfTangent)
        val margin = minOf(staticMargin, ClearPath.RAIL_CLEARANCE_M)
        val toward = if (label.centerX < 0.5f) -1f else 1f
        val wall = 0.5f + toward * lateral / perX
        val inner = wall - toward * margin / perX
        if (toward < 0f) add(-1f, inner, wall, 0f, perX) else add(inner, 2f, wall, 0f, perX)
    }

    /**
     * Cap valide le plus proche de [aim] : la visée si elle passe, sinon le bord
     * de bande valide le plus proche (`null` = tout est bouché). Deux sorties à
     * égalité de cap ([PeoplePath.AXIS_TIE_X]) : le plus grand écart au corps,
     * puis la droite.
     */
    fun select(aim: Float): Float? {
        if (isFree(aim, -1)) return aim
        val left = nearest(aim, below = true)
        val right = nearest(aim, below = false)
        if (left == null || right == null) return left ?: right
        val gap = (aim - left) - (right - aim)
        if (abs(gap) > PeoplePath.AXIS_TIE_X) return if (gap < 0f) left else right
        return if (clearance(left) > clearance(right) + PeoplePath.CLEARANCE_TIE_M) left else right
    }

    /** Sortie valide la plus proche de [aim], du côté [below], `null` si aucune. */
    private fun nearest(aim: Float, below: Boolean): Float? {
        var best: Float? = null
        fun consider(x: Float, skip: Int) {
            if (x < PeoplePath.EDGE_X || x > 1f - PeoplePath.EDGE_X || (x < aim) != below) return
            if (!isFree(x, skip)) return
            val current = best
            if (current == null || abs(x - aim) < abs(current - aim)) best = x
        }
        consider(PeoplePath.EDGE_X, -1)
        consider(1f - PeoplePath.EDGE_X, -1)
        var i = 0
        while (i < count) {
            consider(lo[i], i)
            consider(hi[i], i)
            i++
        }
        return best
    }

    /** Vrai si [x] est hors de toute bande sauf celle de [skip] (son propre bord passe). */
    private fun isFree(x: Float, skip: Int): Boolean {
        var i = 0
        while (i < count) {
            if (i != skip && x > lo[i] + EDGE_EPS && x < hi[i] - EDGE_EPS) return false
            i++
        }
        return true
    }

    /**
     * Écart latéral mini au corps (ou au bord mesuré) le long du cap [x], en
     * mètres, jamais négatif : le cap qui traverse un corps vaut 0.
     */
    fun clearance(x: Float): Float {
        var gap = Float.MAX_VALUE
        var i = 0
        while (i < count) {
            val free = abs(x - centers[i]) * widths[i] - bodies[i]
            if (free < gap) gap = free
            i++
        }
        return gap.coerceAtLeast(0f)
    }
}
