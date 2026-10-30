package dev.sam3vision.core.crossing

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.live.SteeringCap
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Phrases du **mode Traversée** : ce que dit iSee à une personne aveugle au
 * bord du trottoir. Trois phrases seulement, une par [CrossingState], courtes
 * (le vert piéton ne dure pas) et sans distance (la couleur suffit).
 *
 * Comme [dev.sam3vision.core.live.GuidanceText], tout est pur et testé en JVM.
 */
object CrossingText {

    /**
     * Phrase pour [state] dans [language]. Le doute parle comme le rouge
     * (« attendez ») : le silence seul ne dit pas pourquoi on ne traverse pas.
     */
    fun phrase(state: CrossingState, language: SpeechLanguage = SpeechLanguage.Fr): String =
        when (language) {
            SpeechLanguage.En -> when (state) {
                CrossingState.Red -> "Red, wait."
                CrossingState.Green -> "Green, cross now."
                CrossingState.Unknown -> "Unsure, wait."
            }
            SpeechLanguage.Fr -> when (state) {
                CrossingState.Red -> "Rouge, attendez."
                CrossingState.Green -> "Vert, traversez."
                CrossingState.Unknown -> "Doute, attendez."
            }
        }


    /**
     * Annonce d'un **passage piéton reconnu**, hors mode Traversée : elle sert à
     * proposer le mode à quelqu'un qui ne voit pas la pastille. Elle dit quoi
     * faire (« dites traverser ») et non seulement ce qu'on voit.
     */
    fun offer(language: SpeechLanguage = SpeechLanguage.Fr): String =
        when (language) {
            SpeechLanguage.En -> "Crosswalk. Say cross for crossing mode."
            SpeechLanguage.Fr -> "Passage piéton. Dites traverser."
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
    fun mode(on: Boolean, language: SpeechLanguage = SpeechLanguage.Fr): String =
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
        language: SpeechLanguage = SpeechLanguage.Fr,
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
    fun arrived(language: SpeechLanguage = SpeechLanguage.Fr): String =
        when (language) {
            SpeechLanguage.En -> "On the crosswalk."
            SpeechLanguage.Fr -> "Sur le passage."
        }
}
