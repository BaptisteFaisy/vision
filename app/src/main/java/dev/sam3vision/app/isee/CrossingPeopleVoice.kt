package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingCue
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.LateralNudgeTracker
import dev.sam3vision.core.live.PersonLabels
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrase traversée de la frame : le feu d'abord ([cue], jamais couvert), la
 * voix douce des personnes sinon ([CrossingPeopleWatch], nourrie à chaque
 * frame pour suivre les approches même quand le feu parle).
 */
internal fun crossingPhraseOf(
    cue: CrossingCue?,
    placed: List<PlacedLabel>,
    atMs: Long,
    language: SpeechLanguage,
): String? {
    val people = CrossingPeopleWatch.update(placed, atMs, language, cue)
    return cue?.phrase ?: people
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
    private var lastWord: String? = null
    private var lastCuePhrase: String? = null
    private var lastCueMs: Long = Long.MIN_VALUE

    /**
     * Voix douce de la frame, `null` = silence (rien, déjà dit, le feu parle,
     * ou ordre du feu à laisser entier). [cue] non nul = le feu parle sur
     * cette frame : l'ordre est mémorisé (priorité ci-dessous) et la voix
     * douce se tait sans consommer sa scène.
     */
    fun update(
        placed: List<PlacedLabel>,
        atMs: Long,
        language: SpeechLanguage,
        cue: CrossingCue? = null,
    ): String? {
        if (cue != null && cue.phrase != lastCuePhrase) {
            lastCuePhrase = cue.phrase
            lastCueMs = atMs
        }
        val push = nudge.onFrame(atMs, placed)
        if (cue != null) {
            lastWord = null
            return null
        }
        if (cueRecent(atMs)) {
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
        lastWord = null
        lastCuePhrase = null
        lastCueMs = Long.MIN_VALUE
    }

    /** Ordre du feu nouveau et récent : la voix douce se tait pour le laisser entier. */
    private fun cueRecent(atMs: Long): Boolean = lastCueMs != Long.MIN_VALUE &&
        atMs >= lastCueMs && atMs - lastCueMs < CUE_QUIET_MS

    /**
     * Silence doux après un ordre du feu nouveau (2,5 s, le temps de le
     * synthétiser et de le dire) : sans lui, le `collectLatest` côté sonde
     * jetterait l'ordre pas encore joué dès qu'une personne parlerait — un
     * « Vert, traversez. » dit une seule fois ne se rattrape pas. Les
     * répétitions du rouge (même phrase) ne réarment pas.
     */
    const val CUE_QUIET_MS = 2_500L
}
