package dev.sam3vision.core.live

import dev.sam3vision.core.curb.CurbEdge
import kotlin.math.floor
import kotlin.math.min

/**
 * Bornes de la décision de [Guidance] quand la foule est dense : portée qui
 * suit la vitesse de marche, et espace réellement disponible de chaque côté.
 *
 * Extrait de `Guidance` (plafond 500 lignes) sans changer un comportement —
 * l'audit foule du 01/11 a montré que ces deux bornes manquaient : un ordre
 * calculé **1,38 m à gauche** sur un trottoir qui s'arrête à **0,90 m**, et une
 * portée figée à 4 m alors qu'un piéton de face ferme 4 m en 1,43 s.
 *
 * Pur et testé en JVM (aucun Android, aucune horloge).
 */
internal object GuidanceBounds {

    /**
     * Portée effective : la vitesse de marche fait suivre la portée à l'horizon
     * de décision ([Timing.rangeFor] : 5,8 m à 2 m/s), sauf [rangeMeters] fixée
     * explicitement par l'appelant — elle fait foi. Sans vitesse, portée
     * historique ([Guidance.RANGE_METERS]) : rien ne change pour les appelants
     * muets.
     */
    fun effectiveRange(rangeMeters: Float, speedMps: Float?): Float {
        if (speedMps == null || rangeMeters != Guidance.RANGE_METERS) return rangeMeters
        return Timing.rangeFor(speedMps)
    }

    /**
     * Côté conseillé tenu par l'espace libre : si [chosen] n'a pas
     * [FreeSpace.EDGE_MARGIN_M] de place, on prend [FreeSpace.side] (le côté le
     * plus large) quand il a la marge ; aucun côté n'a la marge ⇒ [chosen] est
     * gardé. Jamais `null` : un trottoir étroit ne fait pas taire le guidage.
     */
    fun boundedSide(chosen: GuidanceSide, free: FreeSpace): GuidanceSide {
        if (free.canShift(chosen, FreeSpace.EDGE_MARGIN_M)) return chosen
        val widest = free.side
        return if (free.canShift(widest, FreeSpace.EDGE_MARGIN_M)) widest else chosen
    }

    /**
     * Place du côté [side], `null` quand l'espace n'est **pas mesuré**
     * ([FreeSpace.unknown] : trottoir supposé de 0,90 m) — on ne plafonne pas
     * un ordre par une supposition, la géométrie historique reste seule juge.
     */
    fun measuredRoom(free: FreeSpace, side: GuidanceSide): Float? =
        if (free == FreeSpace.unknown()) null else free.room(side)

    /**
     * Décalage ramené à la place réelle : au plus [roomM], arrondi au demi-mètre
     * **inférieur** (plancher 0). Un ordre de 1,50 m sur 0,60 m de place est un
     * pas dans le mur ; `null` sans décalage ou sans place mesurée.
     */
    fun boundedShift(shiftMeters: Float?, roomM: Float?): Float? {
        val shift = shiftMeters ?: return null
        val room = roomM?.takeIf { it.isFinite() } ?: return shift
        val cap = floor(room / Guidance.SHIFT_STEP_M) * Guidance.SHIFT_STEP_M
        return min(shift, cap.coerceAtLeast(0f))
    }

    /**
     * Cap latéral : passage le plus libre des deux autres secteurs, borné par
     * l'espace libre (côté et décalage chiffré). Extrait de `Guidance.decide`
     * (plafond 50 lignes/fonction, audit foule P0).
     */
    fun lateralDecision(
        cleaned: List<PlacedLabel>,
        obstacle: PlacedLabel,
        cap: SteeringCap,
        range: Float,
        hfovDeg: Float,
        free: FreeSpace,
        curbContact: Boolean,
        curbEdge: CurbEdge?,
    ): GuidanceDecision {
        val passage = Guidance.bestPassage(cleaned, cap, range, hfovDeg)
        val bounded = sideOf(passage)?.let { boundedSide(it, free) }
        val shift = Guidance.shiftOf(obstacle.meters, Guidance.LATERAL_HALF_CORRIDOR, hfovDeg)
        return GuidanceDecision(
            true, obstacle, null, cap, bounded?.let { capOf(it) } ?: passage,
            boundedShift(shift, measuredRoom(free, bounded ?: free.side)),
            curbContact, curbEdge,
        )
    }

    /** Côté vu comme cap. */
    private fun capOf(side: GuidanceSide): SteeringCap = when (side) {
        GuidanceSide.Left -> SteeringCap.Left
        GuidanceSide.Right -> SteeringCap.Right
    }

    /** Cap vu comme côté, `null` pour tout droit : l'axe n'a pas de côté. */
    fun sideOf(cap: SteeringCap): GuidanceSide? = when (cap) {
        SteeringCap.Left -> GuidanceSide.Left
        SteeringCap.Right -> GuidanceSide.Right
        SteeringCap.Straight -> null
    }
}
