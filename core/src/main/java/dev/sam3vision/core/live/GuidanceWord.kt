package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Les quatre mots du guidage (ordre user du 29/10) : la voix ne dit que ça.
 * Un mot par changement de consigne, en moins de 100 ms (clips
 * pré-synthétisés `guide_*`, jamais de synthèse réseau au moment de parler).
 *
 * Les textes sont le miroir du catalogue (`tools/voicepack-catalog.json`,
 * gardé par `tools/check-voicepack.py`) : tout écart casse la CI.
 */
enum class GuidanceWord {
    Straight,
    Left,
    Right,
    Stop,
    ;

    /** Mot prononcé, langue de la voix (littéraux du catalogue, au caractère près). */
    fun text(language: SpeechLanguage): String = when (language) {
        SpeechLanguage.En -> when (this) {
            Straight -> "Straight."
            Left -> "Left."
            Right -> "Right."
            Stop -> "Stop."
        }
        else -> when (this) {
            Straight -> "Tout droit."
            Left -> "Gauche."
            Right -> "Droite."
            Stop -> "Stop."
        }
    }

    /** Entrée catalogue du mot (`vp_<id>_<lang>.mp3`). */
    val clipId: String get() = when (this) {
        Straight -> "guide_straight"
        Left -> "guide_left"
        Right -> "guide_right"
        Stop -> "guide_stop"
    }

    /** Signature anti-répétition : le mot suffit, tout le reste est pareil. */
    val key: String get() = "w|" + name.lowercase()

    companion object {
        /** Mot du cap marché (voie libre) ou du passage conseillé. */
        fun fromCap(cap: SteeringCap): GuidanceWord = when (cap) {
            SteeringCap.Left -> Left
            SteeringCap.Straight -> Straight
            SteeringCap.Right -> Right
        }

        /** Mot du côté de sortie (couloir bouché, cap droit). */
        fun fromSide(side: GuidanceSide): GuidanceWord = when (side) {
            GuidanceSide.Left -> Left
            GuidanceSide.Right -> Right
        }

        /**
         * Entrée catalogue d'une phrase parlée, `null` si ce n'est pas un mot
         * du guidage (positions, trottoir, escaliers : synthèse réseau).
         */
        fun clipIdFor(phrase: String): String? = when (phrase) {
            "Straight.", "Tout droit." -> Straight.clipId
            "Left.", "Gauche." -> Left.clipId
            "Right.", "Droite." -> Right.clipId
            "Stop." -> Stop.clipId
            else -> null
        }

        /**
         * Vrai si [phrase] est le mot stop, FR ou EN (le même « Stop. » des
         * deux côtés) : le seul mot qui coupe la lecture en cours (guidage
         * doux, ordre user du 29/10 — les autres mots attendent le silence).
         */
        fun isStopPhrase(phrase: String): Boolean = phrase == "Stop."
    }
}
