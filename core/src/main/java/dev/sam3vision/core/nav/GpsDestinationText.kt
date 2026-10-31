package dev.sam3vision.core.nav

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * La destination GPS en cours, mise en mots pour le LLM : tant que le
 * guidage est actif, les questions (« c'est encore loin ? », « c'est quoi
 * cette adresse ? ») partent avec cette ligne en contexte — le modèle
 * répond sur la bonne destination au lieu de décrire la photo.
 *
 * Le contexte suit la **langue de la voix** ([SpeechLanguage]), comme le
 * journal caméra ([dev.sam3vision.core.live.TimelineText]) : une ligne
 * française dans une consigne anglaise ferait dériver la réponse.
 */
object GpsDestinationText {

    /** Ligne de contexte pour [destination], `null` si blanche ou absente. */
    fun line(
        destination: String?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val clean = destination?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return when (language) {
            SpeechLanguage.Fr -> "Destination GPS en cours : « $clean »."
            SpeechLanguage.En -> "Active GPS destination: \"$clean\"."
        }
    }

    /**
     * [context] (journal caméra, notes) augmenté de la destination, séparés
     * par `\n\n` comme les blocs du journal ; sans destination, [context]
     * tel quel ; sans les deux, `null` (question simple, sans mémoire).
     */
    fun combine(
        context: String?,
        destination: String?,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val line = line(destination, language) ?: return context
        if (context.isNullOrBlank()) return line
        return "$context\n\n$line"
    }
}
