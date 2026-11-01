package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.traverse.VehicleStateCue

/**
 * Échelle voix dehors SPEC v4 : 13 niveaux, le plus petit gagne —
 * l'écrasé n'est jamais redit sauf re-détecté actuel. 0 voitures en
 * mouvement (< 20 m, présence seule, pluriel si plusieurs) ; 1 parole du
 * user (seul 0 la coupe) ; 2 humains (< 10 m, phrase + cadran + distance,
 * imminent 2 m ou moins = mot immédiat) ; 3 rappel GPS (silence 15 s ou
 * plus) ; 4 grillages, murs et barrières (< 6 m) ; 5 bordures (< 6 m) ;
 * 6 escaliers (jalons 4/2/1, marches comptées) ; 7 poteaux, mobilier
 * vertical et divers (< 6 m) ; 8 passages piétons (< 6 m) ; 9 réponses du
 * chat ; 10 trottoirs ; 11 manœuvres et événements GPS ; 12 repli
 * (gauche, droite ou tout droit).
 */
object OutdoorLevel {
    /** Voitures en mouvement : présence seule, sans cadran ni distance. */
    const val CAR_MOVING = 0

    /** Parole du user : tait tout, seul le niveau 0 la coupe. */
    const val USER_SPEECH = 1

    /** Humains : phrase + cadran + distance, mot imminent immédiat. */
    const val HUMAN = 2

    /** Rappel GPS sur silence (dernière consigne + distance restante). */
    const val GPS_REMINDER = 3

    /** Grillages, murs et barrières. */
    const val FENCE = 4

    /** Bordures. */
    const val CURB = 5

    /** Escaliers : jalons 4/2/1 + nombre de marches dit. */
    const val STAIRS = 6

    /** Poteaux, mobilier vertical (lampadaire, panneau) et divers. */
    const val POLE = 7

    /** Passages piétons (annonce, alignement, arrivée). */
    const val CROSSWALK = 8

    /** Réponses du chat (reprise depuis la coupure, seule file restante). */
    const val CHAT = 9

    /** Trottoirs (comportement actuel). */
    const val SIDEWALK = 10

    /** Manœuvres et événements GPS. */
    const val GPS_EVENT = 11

    /** Repli sur silence (gauche, droite ou tout droit). */
    const val FALLBACK = 12
}

/**
 * Traversée exclusive : verdict (feu piéton puis voitures — rouge ou
 * mouvement = ne passe pas, sinon passe), puis humains, puis bordures ;
 * chat et GPS muets.
 */
object CrossingLevel {
    /** Verdict : feu piéton puis voitures (voiture uniquement). */
    const val VERDICT = 100

    /** Humains en Traversée. */
    const val HUMAN = 101

    /** Bordures en Traversée. */
    const val CURB = 102
}

/**
 * Vœu de parole d'une frame : [level] son niveau sur l'échelle, [text] sa
 * phrase, [key] sa clé d'antirépétition ([AntiRepeat]), [clipId] son clip
 * quand il est connu (zéro réseau, départ immédiat).
 */
internal data class VoiceWish(
    val level: Int,
    val text: String,
    val key: String,
    val clipId: String? = null,
)

/**
 * Décision d'une frame : le vœu de plus petit niveau gagne, les autres
 * sont écrasés (jamais redits) ; `null` = silence. Pur, testé en JVM.
 */
internal fun decideFrame(wishes: List<VoiceWish?>): VoiceWish? =
    wishes.filterNotNull().filter { it.text.isNotBlank() }.minByOrNull { it.level }

/**
 * Vœu véhicule : le mouvement seul parle (niveau 0, clip quand la phrase
 * est fixe) — arrêt et stationnement muets (la spec ne dit que le
 * mouvement). Pur, testé en JVM.
 */
internal fun vehicleWish(cue: VehicleStateCue?): VoiceWish? {
    if (cue == null || !cue.moving) return null
    return VoiceWish(OutdoorLevel.CAR_MOVING, cue.phrase, AntiRepeat.KEY_CAR_MOVING, recordedClipId(cue.phrase))
}

/**
 * Vœu danger : personne (niveau 2 — le mot imminent part en clip immédiat,
 * même niveau), barrière (niveau 4), divers (niveau 7) ; en Traversée, la
 * personne seule parle (niveau 101). Pur, testé en JVM.
 */
internal fun hazardWish(cue: HazardCue?, crossingActive: Boolean = false): VoiceWish? {
    if (cue == null) return null
    if (crossingActive) {
        if (cue.kind != HazardKind.PERSON) return null
        return personWish(cue, CrossingLevel.HUMAN)
    }
    return when (cue.kind) {
        HazardKind.PERSON -> personWish(cue, OutdoorLevel.HUMAN)
        HazardKind.BARRIER -> VoiceWish(OutdoorLevel.FENCE, cue.phrase, AntiRepeat.KEY_FENCE)
        HazardKind.OTHER -> VoiceWish(OutdoorLevel.POLE, cue.phrase, AntiRepeat.KEY_POLE)
    }
}

/** Vœu personne : mot imminent en clip, sinon phrase douce (même niveau). */
private fun personWish(cue: HazardCue, level: Int): VoiceWish =
    if (cue.urgent) VoiceWish(level, cue.phrase, AntiRepeat.KEY_HUMAN_IMMINENT, GuidanceWord.clipIdFor(cue.phrase))
    else VoiceWish(level, cue.phrase, AntiRepeat.KEY_HUMAN_SOFT)

/**
 * Vœu bordure : niveau 5 dehors, 102 en Traversée. Pur, testé en JVM.
 */
internal fun curbWish(cue: CurbCue?, crossingActive: Boolean = false): VoiceWish? {
    if (cue == null) return null
    return VoiceWish(
        if (crossingActive) CrossingLevel.CURB else OutdoorLevel.CURB,
        cue.phrase, AntiRepeat.KEY_CURB,
    )
}

/**
 * Propose un vœu au bus : tu par l'antirépétition ([AntiRepeat]) ou écrasé
 * par le bus ([VoiceBus]) = `false` (jeté, jamais redit) ; sinon posté.
 */
internal fun offer(wish: VoiceWish?, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (wish == null || wish.text.isBlank()) return false
    if (!AntiRepeat.allow(wish.key, nowMs)) return false
    VoiceBus.post(wish.level, wish.text, wish.clipId)
    return true
}
