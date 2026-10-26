package dev.sam3vision.core.speech

/**
 * Langue de la **voix** de l'app : ce que la personne nous dit (annonces de scène,
 * réponses du chat lues à voix haute) et la langue dans laquelle le LLM répond.
 *
 * L'interface reste en français : seul le parler change. C'est le réglage « Voix »
 * des Réglages, persisté (`speech_language` / `speechLanguage`).
 *
 * [code] est le code ISO utilisé par ElevenLabs, [llmName] le nom de la langue tel
 * qu'on l'écrit dans une consigne adressée au modèle.
 */
enum class SpeechLanguage(
    /** Libellé du bouton des Réglages : tient sur une ligne, en pleine largeur. */
    val label: String,
    /** Code court (« fr », « en ») pour les journaux et les tests. */
    val code: String,
    /** Nom de la langue écrit dans les consignes envoyées au LLM. */
    val llmName: String,
) {
    Fr(label = "Voix en français (fr)", code = "fr", llmName = "français"),
    En(label = "Voix en anglais (en)", code = "en", llmName = "English"),
    ;

    companion object {
        /**
         * Langue par défaut : **anglais**. L'intro embarquée (`res/raw/intro_ihear.mp3`,
         * voix Jade US) est déjà en anglais, et c'est la demande du 2026-10-26 :
         * « dans l'app ihear, la personne nous parle en anglais ». Le français reste
         * à un bouton de distance dans les Réglages.
         */
        val DEFAULT: SpeechLanguage = En

        /** Nom persisté → langue ; valeur inconnue ou absente ⇒ [DEFAULT]. */
        fun fromName(name: String?): SpeechLanguage =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT

        /** Code ISO → langue (« en » → [En]) ; code inconnu ⇒ [DEFAULT]. */
        fun fromCode(code: String?): SpeechLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}