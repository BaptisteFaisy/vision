package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Les quatre mots TwinLite de l'obstacle (ordre user du 29/10) : quand le
 * couloir est bouché, c'est cette voix qui parle — consigne d'arrêt au
 * contact (« obstacle, arrêtez-vous. »), côté de sortie sinon. Sur voie
 * libre, les mots SAM ([GuidanceWord]) reprennent : un seul parle à la fois,
 * jamais de doublon ([GuidanceAnnouncer] arbitre, une seule porte).
 *
 * Restauration minimale du chemin voix-obstacle viré par `d13ac26a`
 * (PR #306, guidage SAM seul) : les textes sont ceux de `TwinLiteText`
 * (`7d133f3f`), les clips `tl_*` ceux de `TwinLiteVoice.idFor` (même SHA).
 * Ni segmentation, ni LiteRT, ni synthèse réseau au moment de parler : clips
 * pré-synthétisés, dit en moins de 100 ms comme les mots SAM.
 *
 * Les textes sont le miroir du catalogue (`tools/voicepack-catalog.json`,
 * gardé par `tools/check-voicepack.py`) : tout écart casse la CI.
 */
enum class TwinLiteWord {
    Straight,
    Left,
    Right,
    Stop,
    ;

    /** Mot prononcé, langue de la voix (littéraux du catalogue, au caractère près). */
    fun text(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.En -> when (this) {
            Straight -> "straight ahead."
            Left -> "left."
            Right -> "right."
            Stop -> "obstacle, stop."
        }
        else -> when (this) {
            Straight -> "tout droit."
            Left -> "gauche."
            Right -> "droite."
            Stop -> "obstacle, arrêtez-vous."
        }
    }

    /** Entrée catalogue du mot (`vp_<id>_<lang>.mp3`). */
    val clipId: String get() = when (this) {
        Straight -> "tl_straight"
        Left -> "tl_left"
        Right -> "tl_right"
        Stop -> "tl_stop"
    }

    /** Signature anti-répétition : espace `tl|`, séparé des mots SAM (`w|`). */
    val key: String get() = "tl|" + name.lowercase()

    companion object {
        /** Mot TwinLite du mot SAM bouché : même consigne, voix de l'obstacle. */
        fun fromGuidance(word: GuidanceWord): TwinLiteWord = when (word) {
            GuidanceWord.Straight -> Straight
            GuidanceWord.Left -> Left
            GuidanceWord.Right -> Right
            GuidanceWord.Stop -> Stop
        }

        /**
         * Entrée catalogue d'une phrase parlée, `null` si ce n'est pas un mot
         * TwinLite (mots SAM, positions, trottoir : autre canal).
         */
        fun clipIdFor(phrase: String): String? = when (phrase) {
            "straight ahead.", "tout droit." -> Straight.clipId
            "left.", "gauche." -> Left.clipId
            "right.", "droite." -> Right.clipId
            "obstacle, stop.", "obstacle, arrêtez-vous." -> Stop.clipId
            else -> null
        }

        /**
         * Vrai si [phrase] est le stop TwinLite, FR ou EN : le seul mot qui
         * coupe la lecture en cours (guidage doux — les côtés TwinLite
         * attendent le silence, comme les mots SAM doux).
         */
        fun isStopPhrase(phrase: String): Boolean =
            phrase == Stop.text(SpeechLanguage.Fr) || phrase == Stop.text(SpeechLanguage.En)
    }
}
