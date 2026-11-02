package dev.sam3vision.core.live

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre vocal d'entrée en mode Maison : dire « maison », « house » ou
 * « mode maison » (ou « home mode ») ouvre le direct sur les essentiels. Appelé sur le texte
 * dicté avant le chemin question → LLM (et avant la navigation) — une
 * commande ne fait jamais d'appel réseau.
 *
 * Le mot isolé ou le duo « mode » + « maison » comptent : « va à la maison »
 * reste une navigation, « je veux rentrer à la maison » part comme avant —
 * le mode ne vole ni destination ni phrase (aucune forme de navigation ne
 * contient « mode »). Tolérant : casse, accents et ponctuation neutralisés
 * par [KnownSizes.canonical]. Pur et testé en JVM.
 */
object MaisonEnterCommand {

    /** Le mot qui fait entrer, en français et en anglais (canoniques). */
    private val ENTER_WORDS = setOf("maison", "house")

    /** Vrai si [text] dicté ordonne d'entrer en mode Maison. */
    fun isEnterOrder(text: String): Boolean {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        if (words.size == 1 && ENTER_WORDS.contains(words[0])) return true
        return words.contains("mode") && (words.contains("maison") || words.contains("house") || words.contains("home"))
    }
}

/**
 * Confirmation parlée d'entrée en mode Maison, dite par le même chemin
 * audio que la navigation. Pure et testée en JVM.
 */
object MaisonEnterText {

    /** Le direct maison s'ouvre : le dire, dans la langue de la voix. */
    fun entered(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Mode maison."
        SpeechLanguage.En -> "Home mode."
    }

    /** Déjà en mode maison : le confirmer sans relancer le direct. */
    fun already(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Déjà actif."
        SpeechLanguage.En -> "Already on."
    }
}
