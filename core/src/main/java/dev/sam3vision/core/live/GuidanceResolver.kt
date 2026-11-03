package dev.sam3vision.core.live

import dev.sam3vision.core.hazard.HazardConcepts
import dev.sam3vision.core.model.Passage

/**
 * Décision de couloir de la frame : locale ([Guidance.decide]) sauf passage
 * serveur ([Passage]) qui la remplace — le serveur ne juge que les personnes,
 * donc un mur qui bouche localement reprend la main sur son « libre ».
 * Pur JVM, extrait de [GuidanceAnnouncer.onFrame] (plafond 50 lignes).
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
    val server = samPassage?.let { samDecision(it, labels, cap, hfovDeg, rangeMeters) }
    if (server != null && !server.blocked && wallBlocks(local)) return local
    return server ?: local
}

/**
 * Le serveur ne juge que les personnes ([Passage]) : un mur qui bouche
 * le couloir localement reprend la main sur un « libre » serveur (ordre
 * user : les murs s'esquivent, ils ne se disent pas — et ils comptent
 * pour décider gauche/droite).
 */
private fun wallBlocks(decision: GuidanceDecision): Boolean =
    decision.blocked && decision.obstacle?.label?.let { HazardConcepts.isWall(it) } == true

/**
 * Décision lue dans le passage SAM : bouché et côté viennent du serveur
 * (les mêmes boîtes, jugées à pleine résolution), l'obstacle chiffré des
 * mesures du téléphone. Sans côté (contrat rompu), la gauche — comme
 * l'égalité côté serveur. Veto local 1 m : si le côté serveur ne
 * garantit pas 1 m à une personne et que l'autre côté les garantit,
 * on bascule (la sécurité locale prime sur le juge lointain).
 */
private fun samDecision(
    passage: Passage,
    labels: List<PlacedLabel>,
    cap: SteeringCap,
    hfovDeg: Float,
    range: Float,
): GuidanceDecision {
    if (!passage.blocked) return GuidanceDecision(blocked = false, cap = cap)
    val side = if (passage.side == "right") GuidanceSide.Right else GuidanceSide.Left
    val other = if (side == GuidanceSide.Right) GuidanceSide.Left else GuidanceSide.Right
    val use = if (!PeoplePath.clearsSide(labels, side, range, hfovDeg) &&
        PeoplePath.clearsSide(labels, other, range, hfovDeg)
    ) {
        other
    } else {
        side
    }
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
