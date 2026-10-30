package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.crossing.CrossingCueQuiet
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrase traversée de la frame : le feu d'abord ([cue], jamais couvert), les
 * véhicules moteurs ensuite (en mouvement ou à l'arrêt), la voix douce des
 * personnes sinon ([CrossingPeopleWatch]). Chaque voix douce est nourrie à
 * chaque frame pour suivre les approches même quand le feu parle ; les
 * personnes ne sont consultées que sur le silence des véhicules, pour ne pas
 * perdre un mot de décalage au profit d'une phrase jetée.
 */
internal fun crossingPhraseOf(
    cue: CrossingCue?,
    placed: List<PlacedLabel>,
    detections: List<Detection>,
    readings: List<DistanceReading>,
    atMs: Long,
    language: SpeechLanguage,
): String? {
    if (cue != null) {
        CrossingVehicleWatch.update(detections, readings, atMs, language, cue)
        CrossingPeopleWatch.update(placed, atMs, language, cue)
        return cue.phrase
    }
    CrossingVehicleWatch.update(detections, readings, atMs, language, null)?.let { return it }
    return CrossingPeopleWatch.update(placed, atMs, language, null)
}

/**
 * Voix douce des personnes en mode Traversée, une par processus (même garde
 * des 500 lignes que `CrossingOfferWatch`) : le décalage opposé aux gens
 * proches d'abord (« Droite. », mot nouveau seul, clip < 100 ms — miroir du
 * guidage normal), leurs positions au cadran ensuite (« Personne à 9 heures,
 * à 2 mètres »), débouncées comme toute annonce.
 */
internal object CrossingPeopleWatch {

    private val announcer = PositionAnnouncer()
    private val nudge = LateralNudgeTracker()
    private val quiet = CrossingCueQuiet()
    private var lastWord: String? = null

    /**
     * Voix douce de la frame, `null` = silence (rien, déjà dit, le feu parle,
     * ou ordre du feu à laisser entier). [cue] non nul = le feu parle sur
     * cette frame : l'ordre est mémorisé (priorité ci-dessous) et la voix
     * douce se tait sans consommer sa scène. Le décalage est nourri avant le
     * silence : il traverse le quiet et parle après.
     */
    fun update(
        placed: List<PlacedLabel>,
        atMs: Long,
        language: SpeechLanguage,
        cue: CrossingCue? = null,
    ): String? {
        val push = nudge.onFrame(atMs, placed)
        if (quiet.hush(cue, atMs)) {
            lastWord = null
            return null
        }
        if (push != null) {
            val word = GuidanceWord.fromCap(push.side).text(language)
            if (word == lastWord) return null
            lastWord = word
            return word
        }
        lastWord = null
        return announcer.onFrame(atMs, placed.filter { PersonLabels.isPerson(it.label) }, language)
    }

    /** Mode coupé : la prochaine traversée ré-annonce sa scène. */
    fun reset() {
        announcer.reset()
        nudge.reset()
        quiet.reset()
        lastWord = null
    }
}
