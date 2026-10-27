package dev.sam3vision.core.speech

import dev.sam3vision.core.distance.KnownSizes

/**
 * Ordre vocal d'ouverture des Réglages : « settings », « open settings »,
 * « réglages », « paramètres ». Appelé sur le texte dicté avant l'envoi au LLM,
 * comme la vitesse ou la traversée — une commande ne fait jamais d'appel réseau.
 *
 * Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical]. Pur et testé en JVM ([SettingsVoiceCommandTest]).
 */
object SettingsVoiceCommand {

    /** Mots qui, seuls, désignent les réglages (« settings », « paramètres »). */
    private val SETTINGS_WORDS = setOf(
        "settings",
        "setting",
        "reglage",
        "reglages",
        "parametre",
        "parametres",
        "parameter",
        "parameters",
    )

    /**
     * Vrai si [text] dicté demande les réglages ; `false` = le texte part au LLM
     * comme avant.
     */
    fun isSettingsOrder(text: String): Boolean {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }.toSet()
        return words.any { SETTINGS_WORDS.contains(it) }
    }
}
