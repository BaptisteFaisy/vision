package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases du **mode Traversée** : ce que dit iSee à une personne aveugle au
 * bord du trottoir. Deux phrases ([phrase]), une par [CrossingState],
 * courtes (le vert piéton ne dure pas) et sans distance (la couleur
 * suffit), plus les cas temporels ([blinking], [ready], [contradiction]).
 *
 * Comme [dev.sam3vision.core.live.GuidanceText], tout est pur et testé en JVM.
 */
object CrossingText {

    /**
     * Phrase pour [state] dans [language]. Binaire : rouge ou vert, pas de
     * doute (ordre user du 31/10) — tout non-vert dit « attendez ».
     */
    fun phrase(state: CrossingState, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> when (state) {
                CrossingState.Red -> "Don't cross yet"
                CrossingState.Green -> "You can cross"
            }
            SpeechLanguage.Fr -> when (state) {
                CrossingState.Red -> "Rouge."
                CrossingState.Green -> "Vert."
            }
        }

    /**
     * Vert clignotant (fin de vert) : ne pas s'engager — parlé par
     * [CrossingAnnouncer] avec veto du vert, urgent si un vert était annoncé.
     */
    fun blinking(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "Don't go."
            SpeechLanguage.Fr -> "Attends."
        }

    /**
     * Vert confirmé mais pas encore maintenu (phase PRET du veto, voir
     * câblage `:app`) : « prêt », pas « traversez » — la voix et le bandeau
     * disent tous deux « pas encore ».
     */
    fun ready(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "Ready."
            SpeechLanguage.Fr -> "Prêt."
        }

    /**
     * Deux feux lus en contradiction (un rouge, un vert) : attendre, en le
     * disant — parlé à la place du rouge tant que ça dure.
     */
    fun contradiction(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "Mixed lights."
            SpeechLanguage.Fr -> "Feux mixtes."
        }


    /**
     * Distance fictive jointe aux annonces traversée (mètres) : sous le seuil
     * voix proche, donc dite par le **TTS système, hors ligne** — la sécurité
     * de la traversée ne dépend ni d'une clé ni du réseau.
     */
    const val VOICE_METERS = 0.5f

    /**
     * Confirmation parlée de l'ordre vocal : le mode vient d'être activé
     * ([on] vrai) ou coupé. Courte, dite au TTS système.
     */
    fun mode(on: Boolean, language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> if (on) "Crossing on." else "Crossing off."
            SpeechLanguage.Fr -> if (on) "Traversée on." else "Traversée off."
        }

    /**
     * Position du passage : « Gauche, 2 mètres. » ([cap] = côté,
     * [meters] = distance courte profondeur/sol, jamais sténopé).
     * Sans distance mesurable, la direction seule (« Gauche. »).
     * Jamais de chiffre deviné ; « traversée » implicite (ordre passé).
     * [withHour] vrai + [centerX] renseigné : le cadran suit
     * (« …, à 10 heures. »), via [PositionText.withHour].
     */
    fun approach(
        cap: SteeringCap,
        meters: Float?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        centerX: Float? = null,
        withHour: Boolean = false,
    ): String {
        val valid = meters?.takeIf { it.isFinite() && it > 0f }
        val base = when (language) {
            SpeechLanguage.En -> {
                val head = when (cap) {
                    SteeringCap.Left -> "Left"
                    SteeringCap.Right -> "Right"
                    SteeringCap.Straight -> "Ahead"
                }
                if (valid != null) "$head, ${DistanceText.spokenShort(valid, language)}" else head
            }
            SpeechLanguage.Fr -> {
                val head = when (cap) {
                    SteeringCap.Left -> "Gauche"
                    SteeringCap.Right -> "Droite"
                    SteeringCap.Straight -> "Devant"
                }
                if (valid != null) "$head, ${DistanceText.spokenShort(valid, language)}" else head
            }
        }
        if (withHour && centerX != null) return PositionText.withHour(base, centerX, language)
        return "$base."
    }

    /**
     * Arrivée sur le passage (« Here. » / « Sur le passage. »), douce :
     * `urgent = false`, ne coupe rien), dite une fois par [CrossingGuidance].
     */
    fun arrived(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "Here."
            SpeechLanguage.Fr -> "Sur le passage."
        }
}
