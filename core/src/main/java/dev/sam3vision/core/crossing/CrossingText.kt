package dev.sam3vision.core.crossing

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
}
