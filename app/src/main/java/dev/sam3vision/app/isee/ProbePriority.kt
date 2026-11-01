package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.traverse.VehicleStateCue

/**
 * Priorité voix des obstacles : P1 (voiture en mouvement) > P2 (bordure) >
 * P3 (personne) > P4 (barrière) > reste (arrêt, bateau, divers — dans l'ordre
 * de détection). Quand SAM confirme plusieurs obstacles sur la même frame,
 * l'arbitre ([decideObstacles]) les ordonne au lieu de les faire se couper :
 * tout ce qui est confirmé est dit, dans cet ordre. Extrait de `ProbeWiring`
 * (plafond 500 lignes). Pur, testé en JVM.
 */
internal enum class ObstacleRank { VEHICLE_MOVING, CURB, PERSON, BARRIER, OTHER }

/** Voix de sécurité en cours : une alerte couvre les 2,5 s qui suivent (sous les 3 s de la file douce, pour que le rattrapage parle encore frais — prior, à valider rue). */
internal const val SAFETY_SPEECH_MS = 2_500L

/**
 * Sécurité en cours à [nowMs] : alerte dite il y a moins de [SAFETY_SPEECH_MS] —
 * le doux attend alors en file au lieu de chevaucher. Pur, testé en JVM.
 */
internal fun safetyBusy(nowMs: Long, lastSafetyMs: Long?): Boolean =
    lastSafetyMs != null && nowMs - lastSafetyMs < SAFETY_SPEECH_MS

/**
 * Parole d'une frame : [safety] les alertes fusionnées P2→P3 (`null` = rien
 * d'immédiat), [soft] les phrases douces fusionnées par rang (`null` =
 * silence), [urgent] vrai quand le danger roule sur le user ou que la bordure est
 * imminente (0,7 m), le doux coupe comme avant. Une alerte couverte par la sécurité n'est pas redite en doux.
 */
internal data class ObstacleDecision(val safety: String?, val soft: String?, val urgent: Boolean = false)

/**
 * Arbitre d'une frame en mode normal : les sécurités (bordure, personne
 * imminente — jamais de « Stop » hors Traversée, § 22) fusionnent dans
 * l'ordre des rangs, puis les doux (mouvement P1, bordure à 1 m, personne,
 * barrière, reste en ordre de détection). Le véhicule ne fournit que du
 * doux ici (son stop roulant ne se dit qu'en Traversée, § 22) ; son
 * `urgent` (danger qui s'approche, bordure à 0,7 m) coupe le doux. `null` partout =
 * silence. Pur, testé en JVM.
 */
internal fun decideObstacles(
    vehicle: VehicleStateCue?,
    curb: CurbCue?,
    hazard: HazardCue?,
): ObstacleDecision {
    val safety = listOfNotNull(curb?.safetyPhrase, hazard?.safetyPhrase)
        .joinToString(" ").takeIf { it.isNotEmpty() }
    val soft = softParts(vehicle, curb, hazard).joinToString(" ").takeIf { it.isNotEmpty() }
    return ObstacleDecision(safety, soft, urgent = vehicle?.urgent == true || curb?.urgent == true)
}

/** Phrases douces de la frame, par rang (véhicule, bordure, mobilier à égalité). */
private fun softParts(
    vehicle: VehicleStateCue?,
    curb: CurbCue?,
    hazard: HazardCue?,
): List<String> = listOfNotNull(
    vehicle?.let { softRank(it) to it.phrase },
    curb?.takeIf { it.safetyPhrase == null }?.let { softRank(it) to it.phrase },
    hazard?.takeIf { it.safetyPhrase == null }?.let { softRank(it.kind) to it.phrase },
).sortedBy { it.first }.map { it.second }

/** Rang doux d'un état véhicule : le mouvement loin reste P1, l'arrêt rejoint le reste. */
private fun softRank(cue: VehicleStateCue): ObstacleRank =
    if (cue.moving) ObstacleRank.VEHICLE_MOVING else ObstacleRank.OTHER

/** Rang doux d'une bordure : la pré-alerte à 1 m reste P2, le bateau rejoint le reste. */
private fun softRank(cue: CurbCue): ObstacleRank =
    if (cue.tier == CurbTier.FAR) ObstacleRank.CURB else ObstacleRank.OTHER

/** Rang doux du danger : personne, barrière, puis divers avec le reste. */
private fun softRank(kind: HazardKind): ObstacleRank = when (kind) {
    HazardKind.PERSON -> ObstacleRank.PERSON
    HazardKind.BARRIER -> ObstacleRank.BARRIER
    HazardKind.OTHER -> ObstacleRank.OTHER
}

/**
 * Clip du mot seul d'une personne imminente : l'entrée `guide_*` quand
 * [hazard] est une personne urgente dont la phrase est un mot du guidage
 * (`null` sinon — doux, barrière, divers, sécurité, ou phrase qui n'est
 * pas un mot). Le mot part en clip immédiat, jamais fusionné : la fusion
 * casserait le clipId (départ < 100 ms exigé). Pur, testé en JVM.
 */
internal fun imminentWordClipId(hazard: HazardCue?): String? {
    if (hazard == null || hazard.kind != HazardKind.PERSON) return null
    if (!hazard.urgent || hazard.safetyPhrase != null) return null
    return GuidanceWord.clipIdFor(hazard.phrase)
}
