package dev.sam3vision.core.speech

/**
 * Langue de la **voix** de l'app : ce que la personne nous dit (annonces de scène,
 * réponses du chat lues à voix haute) et la langue dans laquelle le LLM répond.
 *
 * Produit anglophone seul : l'interface comme le parler sont en anglais, sans
 * réglage de langue. [Fr] n'est plus atteignable en production (voir fromStored) :
 * il ne sert qu'aux tests JVM des branches françaises.
 *
 * [code] est le code ISO utilisé par ElevenLabs, [llmName] le nom de la langue tel
 * qu'on l'écrit dans une consigne adressée au modèle.
 */
enum class SpeechLanguage(
    /** Libellé de langue (journaux et tests ; plus aucun bouton en production). */
    val label: String,
    /** Code court (« fr », « en ») pour les journaux et les tests. */
    val code: String,
    /** Nom de la langue écrit dans les consignes envoyées au LLM. */
    val llmName: String,
) {
    Fr(label = "French voice (fr)", code = "fr", llmName = "français"),
    En(label = "English voice (en)", code = "en", llmName = "English"),
    ;

    companion object {
        /**
         * Langue unique : **anglais**. L'intro embarquée (`res/raw/intro_isee.mp3`,
         * voix Laure) est déjà en anglais, et la demande produit est l'anglais
         * exclusif : l'app iSee parle et comprend l'anglais uniquement, sans réglage
         * de langue.
         */
        val DEFAULT: SpeechLanguage = En

        /** Nom persisté → langue ; valeur inconnue ou absente ⇒ [DEFAULT]. */
        fun fromName(name: String?): SpeechLanguage =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: DEFAULT

        /** Code ISO → langue (« en » → [En]) ; code inconnu ⇒ [DEFAULT]. */
        fun fromCode(code: String?): SpeechLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT

        /**
         * Valeur persistée → langue de production : un « Fr » stocké par une version
         * antérieure migre vers l'anglais (produit anglophone seul, sans réglage).
         */
        fun fromStored(name: String?): SpeechLanguage =
            when (fromName(name)) {
                Fr -> En
                En -> En
            }
    }
}