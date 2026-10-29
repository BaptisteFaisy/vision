package dev.sam3vision.core.nav

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre vocal d'entrée en **mode navigation** (la carte) : dire « navigation »,
 * « carte », « plan », « mode navigation », « ouvre la carte », « affiche le
 * plan » ou « où suis-je » ouvre l'écran carte — position GPS à l'écran, sans
 * destination. Appelé sur le texte dicté avant la navigation et avant le
 * chemin question → LLM : une commande ne fait aucun appel réseau.
 *
 * Ce qui n'entre **pas** :
 * - une destination (« navigation vers la gare », « ouvre la carte de la
 *   gare ») : le verbe d'ouverture suivi de « la » est la seule forme
 *   acceptée, et une forme plus longue que [MAX_OPEN_WORDS] mots est laissée
 *   à [NavigationIntentParser] ;
 * - un ordre de sortie (« arrête la navigation », « quitte la carte ») :
 *   [isLeaveOrder] répond `true` et [isEnterOrder] `false`.
 *
 * Même motif que `MaisonEnterCommand` : casse, accents et ponctuation
 * neutralisés par [KnownSizes.canonical]. Pur, sans dépendance, ne lève jamais.
 */
object MapModeCommand {

    /** Mots seuls qui entrent (canoniques). */
    private val ENTER_WORDS = setOf("navigation", "carte", "plan")

    /** Verbes d'ouverture : « ouvre la carte », « affiche le plan ». */
    private val OPEN_VERBS = setOf("ouvre", "ouvrir", "affiche", "afficher", "montre", "montrer")

    /** Verbes de sortie (« quitte la carte », « arrête la navigation »). */
    private val CLOSE_VERBS = setOf(
        "quitte", "quitter", "ferme", "fermer",
        "arrete", "arreter", "stop", "stoppe", "stopper", "annule", "annuler",
    )

    /** « où suis-je » : la carte avec la position, question devenue commande. */
    private val WHERE_AM_I = setOf("ou suis je", "ou je suis", "where am i", "where i am")

    /** Articles et possessifs courts devant le mot seul (« la carte »). */
    private val ARTICLES = setOf(
        "le", "la", "les", "un", "une", "des", "du", "the",
        "mon", "ma", "mes", "ton", "ta", "tes", "notre", "my",
    )

    /** « ouvre la carte » = 3 mots ; au-delà, c'est une phrase avec destination. */
    private const val MAX_OPEN_WORDS = 4

    /** « passe en mode navigation » = 4 mots. */
    private const val MAX_MODE_WORDS = 5

    /** Vrai si [text] dicté ordonne d'ouvrir le mode navigation (la carte). */
    fun isEnterOrder(text: String): Boolean {
        if (text.isBlank() || isLeaveOrder(text)) return false
        val key = KnownSizes.canonical(text)
        if (key in WHERE_AM_I) return true
        val words = key.split(' ').filter { it.isNotEmpty() }
        val core = words.dropWhile { it in ARTICLES }
        if (core.isEmpty()) return false
        if (core.size == 1) return core[0] in ENTER_WORDS
        if (core.none { it in ENTER_WORDS }) return false
        if (words.contains("mode")) return words.size <= MAX_MODE_WORDS
        return core.first() in OPEN_VERBS && core.size <= MAX_OPEN_WORDS
    }

    /** Vrai si [text] ordonne de fermer le mode navigation (carte affichée). */
    fun isLeaveOrder(text: String): Boolean {
        if (text.isBlank()) return false
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        return words.any { it in CLOSE_VERBS } && words.any { it in ENTER_WORDS }
    }
}

/**
 * Confirmation parlée du mode navigation, dite par le même chemin audio que
 * la navigation (ElevenLabs + lecteur partagé). Pure et testée en JVM.
 */
object MapModeText {

    /** La carte s'ouvre : le dire, dans la langue de la voix. */
    fun entered(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Mode navigation. La carte est à l'écran."
        SpeechLanguage.En -> "Navigation mode. The map is on screen."
    }

    /** La carte se ferme : le dire, sans laisser croire à un guidage arrêté. */
    fun left(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Mode navigation fermé."
        SpeechLanguage.En -> "Navigation mode closed."
    }
}
