package dev.sam3vision.core.live

import dev.sam3vision.core.distance.LensSpec

/**
 * Place libre de chaque côté, en fraction d'image : le plus grand trou de la frame à
 * gauche et à droite de l'axe. Égalité à [WidestGap.TIE] près : à droite, s'il y a de
 * quoi y passer — sous cette marge, la droite n'est pas un choix, on ne va pas dans un mur.
 */
data class GapRoom(val left: Float, val right: Float) {
    val side: GuidanceSide
        get() {
            val tie = WidestGap.TIE
            return if (left > right + tie || (right < tie && left > right)) GuidanceSide.Left else GuidanceSide.Right
        }
}

/**
 * Guider vers l'endroit où il y a le plus d'espace libre (ordre user du 04/11).
 *
 * La sortie la plus proche d'un cap tirait à pile ou face sur une personne plein axe :
 * 152 gauche, 128 droite sur le Samsung, le temps d'un objet lointain ou sans distance.
 * Le plus grand trou change peu d'une frame à l'autre, et seul ce qu'on va atteindre le
 * dessine : jusqu'à [BEHIND_M] derrière l'obstacle, jamais moins que la portée proche.
 * Un objet sans distance ni coupé par le bas ne dit pas qu'il est proche : il ne compte pas.
 *
 * Vaut pour tout ce qui barre le chemin : personnes, murs, poteaux, barrières. Une bordure
 * ne se contourne pas, un mur longé ferme son côté à sa distance latérale ([railSpan]).
 *
 * Pas de tenue (ordre user du 03/11) : le côté est recalculé à chaque frame. Le balancement
 * de la marche (0,087 d'image au 90e centile au Samsung) s'efface dans la mesure, par le
 * centre moyen de chaque piste ([ClearPath.axisX]). L'égalité [TIE] reste donc étroite :
 * à 15 %, elle disait droite vers un obstacle décalé à droite (plainte user du 04/11).
 * Pur, testé en JVM.
 */
internal object WidestGap {

    /** Écart de place sous lequel les côtés se valent : un corps à ±3 % de l'axe est plein axe. */
    const val TIE = 0.06f

    /** Profondeur (m) derrière l'obstacle au-delà de laquelle rien ne décide du côté. */
    const val BEHIND_M = 2f

    private class Span(val start: Float, val end: Float)

    /** Place de la frame pour contourner un obstacle à [depthM], `null` si elle est bouchée. */
    fun of(labels: List<PlacedLabel>, depthM: Float, hfovDeg: Float): GapRoom? {
        val halfTangent = LensSpec.halfTangent(hfovDeg)
        if (!halfTangent.isFinite() || halfTangent <= 0f) return null
        val limit = maxOf(depthM + BEHIND_M, Guidance.RANGE_METERS)
        val spans = labels.mapNotNull { spanOf(it, depthM, limit, halfTangent) }.sortedBy { it.start }
        if (spans.isEmpty()) return null
        var left = 0f
        var right = 0f
        var cursor = 0f
        for (span in spans + Span(1f, 1f)) {
            val width = span.start - cursor
            if (width > 0f) {
                if ((cursor + span.start) / 2f < AXIS) left = maxOf(left, width) else right = maxOf(right, width)
            }
            cursor = maxOf(cursor, span.end)
        }
        return GapRoom(left, right).takeIf { left > 0f || right > 0f }
    }

    /** Colonnes prises par [label], épaules comprises, `null` s'il ne compte pas. */
    private fun spanOf(label: PlacedLabel, depthM: Float, limit: Float, halfTangent: Float): Span? {
        if (!ClearPath.bars(label) || !label.centerX.isFinite()) return null
        if (ClearPath.isRail(label)) return railSpan(label, depthM, halfTangent)
        val depth = ClearPath.nearDepth(label)?.takeIf { it <= limit } ?: return null
        val perX = lateralPerX(depth, halfTangent)
        val half = bodyHalfM(label, perX) / perX
        val pad = PeoplePath.BODY_HALF_WIDTH_M / perX
        val center = ClearPath.axisX(label)
        return Span((center - half - pad).coerceAtLeast(0f), (center + half + pad).coerceAtMost(1f))
    }

    /**
     * Mur longé : son bord intérieur est un point de fuite, sa boîte boucherait la moitié
     * de l'image. Il ferme son côté à sa distance latérale, projetée à [depthM].
     */
    private fun railSpan(label: PlacedLabel, depthM: Float, halfTangent: Float): Span {
        val lateral = ClearPath.railLateralM(label, halfTangent) ?: (ClearPath.UNSEEN_DEPTH_M * halfTangent)
        val reach = lateral / lateralPerX(depthM, halfTangent)
        return if (label.centerX < AXIS) {
            Span(0f, (AXIS - reach).coerceAtLeast(0f))
        } else {
            Span((AXIS + reach).coerceAtMost(1f), 1f)
        }
    }

    private const val AXIS = 0.5f
}
