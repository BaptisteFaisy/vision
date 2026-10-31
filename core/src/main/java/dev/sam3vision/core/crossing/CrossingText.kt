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
                CrossingState.Red -> "Rouge, attendez."
                CrossingState.Green -> "Vert, traversez."
            }
        }

    /**
     * Vert clignotant (fin de vert) : ne pas s'engager — parlé par
     * [CrossingAnnouncer] avec veto du vert, urgent si un vert était annoncé.
     */
    fun blinking(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "Flashing green, do not cross."
            SpeechLanguage.Fr -> "Vert clignotant, ne pas s'engager."
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
            SpeechLanguage.En -> "Conflicting lights, wait."
            SpeechLanguage.Fr -> "Feux contradictoires, attendez."
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
            SpeechLanguage.En -> if (on) "Crossing mode on." else "Crossing mode off."
            SpeechLanguage.Fr -> if (on) "Mode traversée activé." else "Mode traversée coupé."
        }

    /**
     * Position du passage piéton sur le trottoir : « Passage piéton à gauche,
     * à 2 mètres. » ([cap] = côté du passage, [meters] = distance courte
     * profondeur/sol, jamais sténopé). Sans distance mesurable, la direction
     * seule (« Passage piéton à gauche. ») — jamais de chiffre deviné.
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
                    SteeringCap.Left -> "Crosswalk to the left"
                    SteeringCap.Right -> "Crosswalk to the right"
                    SteeringCap.Straight -> "Crosswalk ahead"
                }
                if (valid != null) "$head, ${DistanceText.spokenShort(valid, language)} away" else head
            }
            SpeechLanguage.Fr -> {
                val head = when (cap) {
                    SteeringCap.Left -> "Passage piéton à gauche"
                    SteeringCap.Right -> "Passage piéton à droite"
                    SteeringCap.Straight -> "Passage piéton devant"
                }
                if (valid != null) "$head, à ${DistanceText.spokenShort(valid, language)}" else head
            }
        }
        if (withHour && centerX != null) return PositionText.withHour(base, centerX, language)
        return "$base."
    }

    /**
     * Arrivée sur le passage (« Sur le passage. ») : douce (appelant :
     * `urgent = false`, ne coupe rien), dite une fois par [CrossingGuidance].
     */
    fun arrived(language: SpeechLanguage = SpeechLanguage.DEFAULT): String =
        when (language) {
            SpeechLanguage.En -> "On the crosswalk."
            SpeechLanguage.Fr -> "Sur le passage."
        }
}
