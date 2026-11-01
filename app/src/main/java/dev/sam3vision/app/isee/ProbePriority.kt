package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.traverse.VehicleStateCue

/**
 * Priorité voix des obstacles : la voiture en mouvement écrase tout doux,
 * la personne écrase tout doux sauf la voiture (ordre user du 01/11, §
 * 31) — sans mouvement, les rangs P1-P5 ordonnent la fusion comme avant
 * (bordure > barrière > reste, dans l'ordre de détection). Quand SAM
 * confirme plusieurs obstacles sur la même frame, l'arbitre
 * ([decideObstacles]) ne fusionne plus le mouvement : le gagnant parle
 * seul et coupe la parole douce ([VoicePreempt]) ; la sécurité (bordure
 * imminente, jamais de « Stop » hors Traversée, § 22) garde le dessus.
 * Extrait de `ProbeWiring` (plafond 500 lignes). Pur, testé en JVM.
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
 * d'immédiat), [soft] la phrase douce (`null` = silence) — le mouvement
 * gagnant seul (voiture, sinon personne), ou les doux fusionnés par rang
 * sans mouvement —, [urgent] vrai quand le danger roule sur le user ou
 * que la bordure est imminente (0,7 m), le doux coupe comme avant,
 * [preempt] le rang qui coupe la parole douce (voiture > personne,
 * sécurité exceptée). Une alerte couverte par la sécurité n'est pas
 * redite en doux.
 */
internal data class ObstacleDecision(
    val safety: String?,
    val soft: String?,
    val urgent: Boolean = false,
    val preempt: VoicePreempt = VoicePreempt.NONE,
)

/**
 * Arbitre d'une frame en mode normal : les sécurités (bordure, personne
 * imminente — jamais de « Stop » hors Traversée, § 22) fusionnent dans
 * l'ordre des rangs, puis le doux — le mouvement gagnant seul (voiture
 * en mouvement, sinon personne : il écrase bordure, barrière et reste
 * au lieu de fusionner, § 31), ou les doux fusionnés par rang sans
 * mouvement. Le véhicule ne fournit que du doux ici (son stop roulant
 * ne se dit qu'en Traversée, § 22) ; son `urgent` (danger qui
 * s'approche, bordure à 0,7 m) coupe le doux. `null` partout = silence.
 * Pur, testé en JVM.
 */
internal fun decideObstacles(
    vehicle: VehicleStateCue?,
    curb: CurbCue?,
    hazard: HazardCue?,
): ObstacleDecision {
    val safety = listOfNotNull(curb?.safetyPhrase, hazard?.safetyPhrase)
        .joinToString(" ").takeIf { it.isNotEmpty() }
    val mover = vehicle?.takeIf { it.moving }
    val person = hazard?.takeIf { it.kind == HazardKind.PERSON && it.safetyPhrase == null }
    val soft = mover?.phrase
        ?: person?.phrase
        ?: softParts(vehicle, curb, hazard).joinToString(" ").takeIf { it.isNotEmpty() }
    val preempt = when {
        mover != null -> VoicePreempt.VEHICLE
        person != null -> VoicePreempt.PERSON
        else -> VoicePreempt.NONE
    }
    return ObstacleDecision(
        safety,
        soft,
        urgent = vehicle?.urgent == true || curb?.urgent == true,
        preempt = preempt,
    )
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
