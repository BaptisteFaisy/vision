package dev.sam3vision.app.isee

import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.hazard.HazardCue
import dev.sam3vision.core.hazard.HazardKind
import dev.sam3vision.core.crossing.CrossingLevels
import dev.sam3vision.core.live.AntiRepeat
import dev.sam3vision.core.live.GuidanceWord

/**
 * Échelle voix dehors SPEC v4 : 13 niveaux, le plus petit gagne —
 * l'écrasé n'est jamais redit sauf re-détecté actuel. 0 réservé (plus
 * aucun producteur dehors : les voitures ne parlent qu'en Traversée,
 * verdict 100 — ordre user du 02/11) ; 1 parole du user ; 2 humains
 * (< 10 m, phrase + cadran + distance, imminent 2 m ou moins = mot
 * immédiat) ; 3 rappel GPS (silence 15 s ou plus) ; 4 grillages, murs
 * et barrières (< 6 m) ; 5 bordures (< 6 m) ; 6 escaliers (jalons 4/2/1,
 * marches comptées) ; 7 poteaux, mobilier vertical et divers (< 6 m) ;
 * 8 passages piétons (< 6 m) ; 9 réponses du chat ; 10 trottoirs ;
 * 11 manœuvres et événements GPS ; 12 repli (gauche, droite ou tout droit).
 */
object OutdoorLevel {
    /** Niveau 0 réservé (aucun producteur dehors : voitures = Traversée). */
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


/** Étiquette des diagnostics du mutisme spontané (`logcat -s VoiceMute:*`). */
private const val TAG_MUTE = "VoiceMute"

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
    val clips: List<String> = emptyList(),
)

/**
 * Décision d'une frame : le vœu de plus petit niveau gagne, les autres
 * sont écrasés (jamais redits) ; `null` = silence. Pur, testé en JVM.
 */
internal fun decideFrame(wishes: List<VoiceWish?>): VoiceWish? =
    wishes.filterNotNull().filter { it.text.isNotBlank() }.minByOrNull { it.level }

/**
 * Vœu danger : transfère le niveau et la clé du guetteur ([HazardCue.level],
 * [HazardCue.antiRepeatKey] — personne 2, grillages/murs/barrières 4,
 * poteaux et divers 7, imminent déjà clé `human-imminent`) ; le mot imminent
 * part en clip. En Traversée, la personne seule parle (niveau 101). Pur,
 * testé en JVM.
 */
internal fun hazardWish(cue: HazardCue?, crossingActive: Boolean = false): VoiceWish? {
    if (cue == null) return null
    if (crossingActive && cue.kind != HazardKind.PERSON) return null
    val level = if (crossingActive) CrossingLevels.HUMAN else cue.level
    val clip = GuidanceWord.clipIdFor(cue.phrase).takeIf { cue.urgent }
    return VoiceWish(level, cue.phrase, cue.antiRepeatKey, clip)
}

/**
 * Vœu bordure : niveau 5 dehors, 102 en Traversée. Pur, testé en JVM.
 */
internal fun curbWish(cue: CurbCue?, crossingActive: Boolean = false): VoiceWish? {
    if (cue == null) return null
    return VoiceWish(
        if (crossingActive) CrossingLevels.CURB else OutdoorLevel.CURB,
        cue.phrase, AntiRepeat.KEY_CURB,
    )
}

/**
 * Propose un vœu au bus : tu par l'antirépétition ([AntiRepeat]) ou écrasé
 * par le bus ([VoiceBus]) = `false` (jeté, jamais redit) ; sinon posté.
 * Sous un compte (§ 42), seuls les vœux escaliers passent — les autres
 * attendent sans consommer leur tour (le bus les jetterait de toute
 * façon, tour mangé).
 */
internal fun offer(wish: VoiceWish?, nowMs: Long = System.currentTimeMillis()): Boolean {
    if (wish == null || wish.text.isBlank()) return false
    if (VoiceBus.stairsSpeaking() && wish.level != OutdoorLevel.STAIRS) {
        if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "offre tenue-escaliers level=${wish.level} texte=[${wish.text}]")
        return false
    }
    val allowed = AntiRepeat.allow(wish.key, nowMs)
    if (BuildConfig.DEBUG) android.util.Log.d(TAG_MUTE, "offre level=${wish.level} cle=${wish.key} admis=$allowed texte=[${wish.text}]")
    if (!allowed) return false
    VoiceBus.post(wish.level, wish.text, wish.clipId, wish.clips, antiRepeatKey = wish.key)
    return true
}
