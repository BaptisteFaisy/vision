package dev.sam3vision.core.live

import dev.sam3vision.core.model.Passage

/**
 * Décision de couloir de la frame : **locale d'abord** ([Guidance.decide]). Le
 * téléphone a les mètres, les bords de boîte, les murs et le trottoir ; le
 * serveur ([Passage]) ne juge que des centres de boîte en fraction d'image, sans
 * distance ni mur. Son « libre » ne fait donc jamais taire un bouché local, et
 * il ne peut qu'ajouter un bouché que le local n'a pas vu. Pur JVM, extrait de
 * [GuidanceAnnouncer.onFrame] (plafond 50 lignes).
 */
internal fun resolveDecision(
    labels: List<PlacedLabel>,
    cap: SteeringCap,
    rangeMeters: Float,
    hfovDeg: Float,
    free: FreeSpace,
    speedMps: Float?,
    holeSide: GuidanceSide?,
    samPassage: Passage?,
): GuidanceDecision {
    val local = Guidance.decide(
        labels, cap, rangeMeters, hfovDeg, free = free, speedMps = speedMps,
        holeSide = holeSide,
    )
    if (local.blocked || samPassage?.blocked != true) return local
    return samDecision(samPassage, labels, cap, hfovDeg, rangeMeters, free)
}

/**
 * Bouché vu du serveur seul : le côté vient de la place locale quand il y en a
 * (le serveur ignore les murs et enverrait dedans), sinon du serveur, à défaut
 * la gauche — comme l'égalité côté serveur. Veto local 1 m : si ce côté ne
 * garantit pas 1 m à une personne et que l'autre les garantit, on bascule.
 * L'obstacle chiffré vient des mesures du téléphone.
 */
private fun samDecision(
    passage: Passage,
    labels: List<PlacedLabel>,
    cap: SteeringCap,
    hfovDeg: Float,
    range: Float,
    free: FreeSpace,
): GuidanceDecision {
    val use = GuidanceBounds.boundedSide(samSide(passage, labels, hfovDeg, range), free)
    val exit = if (use == GuidanceSide.Right) SteeringCap.Right else SteeringCap.Left
    val obstacle = labels
        .mapNotNull { label -> label.meters?.takeIf { it.isFinite() }?.let { label to it } }
        .minByOrNull { it.second }?.first
    val half = if (cap == SteeringCap.Straight) {
        Guidance.STRAIGHT_HALF_CORRIDOR
    } else {
        Guidance.LATERAL_HALF_CORRIDOR
    }
    return GuidanceDecision(
        blocked = true,
        obstacle = obstacle,
        side = if (cap == SteeringCap.Straight) use else null,
        cap = cap,
        passage = exit,
        shiftMeters = Guidance.shiftOf(obstacle?.meters, half, hfovDeg),
    )
}

/** Côté d'un bouché serveur : place locale d'abord, sinon le sien sous veto personne. */
private fun samSide(passage: Passage, labels: List<PlacedLabel>, hfovDeg: Float, range: Float): GuidanceSide {
    val cleaned = labels.filter { Guidance.isObstacle(it) }
    WidestGap.of(cleaned, range, hfovDeg)?.let { return it.side }
    val side = if (passage.side == "right") GuidanceSide.Right else GuidanceSide.Left
    val other = if (side == GuidanceSide.Right) GuidanceSide.Left else GuidanceSide.Right
    val vetoed = !PeoplePath.clearsSide(labels, side, range, hfovDeg) &&
        PeoplePath.clearsSide(labels, other, range, hfovDeg)
    return if (vetoed) other else side
}
