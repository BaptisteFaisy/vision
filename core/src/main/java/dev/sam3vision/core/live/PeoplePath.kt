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
    companion object {

        /** Écart mini au corps d'une personne (m) : on ne frôle pas les gens. */
        const val MIN_CLEARANCE_M = 0.5f

        /**
         * Demi-largeur de corps (m) quand la boîte est inconnue : les épaules
         * (≈ 0,45 m) plutôt que rien — même prudence que le trait de [Guidance].
         */
        const val BODY_HALF_WIDTH_M = 0.25f

        /** Cap imposé dans `[EDGE_X, 1 - EDGE_X]` : on ne vise jamais le bord de l'image. */
        const val EDGE_X = 0.02f

        /**
         * Cap qui passe au plus près de [aimX] ([Guidance.RANGE_METERS] et champ
         * [hfovDeg] comme le couloir) : `null` si rien n'est à portée, si un
         * obstacle à portée a une géométrie incomplète (sans distance finie > 0
         * ou sans centre fini), ou si le champ ou la visée sont infaisables
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
            if (!bands.collect(labels, rangeMeters, halfTangent)) return null
            val aim = aimX.coerceIn(EDGE_X, 1f - EDGE_X)
            val heading = bands.select(aim)
            if (heading == null) return PeoplePath(aim, sideOf(aim), bands.clearance(aim), false)
            return PeoplePath(heading, sideOf(heading), bands.clearance(heading), true)
        }

        /** Côté du cap : à gauche sous l'axe, à droite dessus (pile au centre ⇒ droite). */
        private fun sideOf(headingX: Float): GuidanceSide =
            if (headingX < 0.5f) GuidanceSide.Left else GuidanceSide.Right
    }
}

/** Frontière de bande : en deçà, le cap est hors bande (le bord lui-même passe). */
private const val EDGE_EPS = 1e-4f

/** Égalité des distances à la visée : en deçà, le plus petit cap gagne (à gauche). */
private const val TIE_EPS = 1e-6f

/**
 * Largeur de champ à [depth] mètres : ce que vaut tout l'axe x en mètres —
 * même projection que le trait du couloir (`halfTangent` borné caméra).
 */
private fun lateralPerX(depth: Float, halfTangent: Float): Float = 2f * depth * halfTangent

/** Bandes interdites en cap et géométrie pour l'écart : tampons pré-alloués, une passe. */
private class ObstacleBands(size: Int) {
    private val lo = FloatArray(size)
    private val hi = FloatArray(size)
    private val centers = FloatArray(size)
    private val bodies = FloatArray(size)
    private val widths = FloatArray(size)
    private var count = 0

    /**
     * Remplit les bandes en une passe : faux si un obstacle à portée a une
     * géométrie incomplète, ou si rien n'est à portée (même règle que le
     * couloir : distance absente ou non finie = à portée, par prudence).
     */
    fun collect(labels: List<PlacedLabel>, rangeMeters: Float, halfTangent: Float): Boolean {
        var index = 0
        while (index < labels.size) {
            val label = labels[index]
            index++
            val meters = label.meters
            if (meters != null && meters.isFinite() && meters > rangeMeters) continue
            val depth = if (meters != null && meters.isFinite() && meters > 0f) {
                meters
            } else {
                return false
            }
            val center = label.centerX
            if (!center.isFinite()) return false
            val lateralPerX = lateralPerX(depth, halfTangent)
            val halfBox = halfBoxM(label, lateralPerX)
            val person = PersonLabels.isPerson(label.label)
            val body = if (person) halfBox ?: PeoplePath.BODY_HALF_WIDTH_M else halfBox ?: 0f
            val margin = if (person) PeoplePath.MIN_CLEARANCE_M else Guidance.RAY_HALF_WIDTH_M
            val half = (body + margin) / lateralPerX
            val start = center - half
            val end = center + half
            if (!start.isFinite() || !end.isFinite()) return false
            lo[count] = start
            hi[count] = end
            centers[count] = center
            bodies[count] = body
            widths[count] = lateralPerX
            count++
        }
        return count > 0
    }

    /**
     * Demi-boîte en mètres, `null` si inconnue (bords absents, non finis ou
     * largeur nulle) : la boîte inversée est tolérée (largeur absolue).
     */
    private fun halfBoxM(label: PlacedLabel, lateralPerX: Float): Float? {
        val x0 = label.x0
        val x1 = label.x1
        if (x0 == null || x1 == null || !x0.isFinite() || !x1.isFinite()) return null
        val width = abs(x1 - x0)
        if (width <= 0f) return null
        return width * 0.5f * lateralPerX
    }

    /**
     * Cap valide le plus proche de [aim] : la visée si elle passe, sinon le
     * bord de bande valide le plus proche (`null` = tout est bouché). À égalité,
     * le plus petit cap (à gauche, comme la V1).
     */
    fun select(aim: Float): Float? {
        if (isFree(aim, -1)) return aim
        var best = 0f
        var bestDist = Float.MAX_VALUE
        var found = false
        fun consider(x: Float, skip: Int) {
            if (x < PeoplePath.EDGE_X || x > 1f - PeoplePath.EDGE_X || !isFree(x, skip)) return
            val dist = abs(x - aim)
            if (!found || dist < bestDist - TIE_EPS ||
                (abs(dist - bestDist) <= TIE_EPS && x < best)
            ) {
                best = x
                bestDist = dist
                found = true
            }
        }
        consider(PeoplePath.EDGE_X, -1)
        consider(1f - PeoplePath.EDGE_X, -1)
        var i = 0
        while (i < count) {
            consider(lo[i], i)
            consider(hi[i], i)
            i++
        }
        return if (found) best else null
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
