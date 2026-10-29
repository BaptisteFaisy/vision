package dev.sam3vision.core.segment

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases du guidage TwinLiteNet : quatre consignes courtes, une par
 * [TwinLiteDirection] — la zone praticable change à chaque pas, la phrase ne
 * doit pas durer plus longtemps que le pas.
 *
 * Comme [dev.sam3vision.core.crossing.CrossingText], tout est pur et testé en JVM.
 */
object TwinLiteText {

    /**
     * Consigne pour [direction] dans [language] : « gauche. », « tout droit. »,
     * « droite. », « obstacle, arrêtez-vous. » (miroir anglais).
     */
    fun phrase(
        direction: TwinLiteDirection,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String = when (language) {
        SpeechLanguage.En -> when (direction) {
            TwinLiteDirection.LEFT -> "left."
            TwinLiteDirection.STRAIGHT -> "straight ahead."
            TwinLiteDirection.RIGHT -> "right."
            TwinLiteDirection.STOP -> "obstacle, stop."
        }
        SpeechLanguage.Fr -> when (direction) {
            TwinLiteDirection.LEFT -> "gauche."
            TwinLiteDirection.STRAIGHT -> "tout droit."
            TwinLiteDirection.RIGHT -> "droite."
            TwinLiteDirection.STOP -> "obstacle, arrêtez-vous."
        }
    }

    /**
     * Signature anti-répétition : `left`, `straight`, `right`, `stop` — toujours
     * anglaise, pour ne pas rebavarder au changement de langue de la voix.
     */
    fun key(direction: TwinLiteDirection): String = when (direction) {
        TwinLiteDirection.LEFT -> "left"
        TwinLiteDirection.STRAIGHT -> "straight"
        TwinLiteDirection.RIGHT -> "right"
        TwinLiteDirection.STOP -> "stop"
    }
}
