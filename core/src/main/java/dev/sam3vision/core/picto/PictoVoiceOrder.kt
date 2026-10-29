package dev.sam3vision.core.picto

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre vocal « fais un pictogramme de X » : le direct photographié part
 * en génération, le repère armé guide ensuite vers X. Appelé sur le texte
 * dicté avant le LLM — une commande ne fait jamais d'appel réseau elle-même
 * (la génération, elle, en fait un).
 *
 * Le mot « pictogramme » (ou « picto ») seul ne suffit pas : « c'est quoi
 * ce pictogramme ? » reste une question pour le LLM. Il faut un verbe de
 * création (« fais », « crée », « dessine »…). Le label rendu est la suite
 * brute après « de », sans ses articles (« ces médics » → « medics ») ;
 * « de ça » rend vide (objet filmé sans nom, overlay seul, jamais guidé).
 *
 * Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical]. Pur et testé en JVM.
 */
object PictoVoiceOrder {

    /** Verbes de création (canoniques) : sans eux, pas d'ordre picto. */
    private val CREATE_VERBS = setOf(
        "fait", "fais", "faire", "faites", "faisons",
        "cree", "creer", "creez",
        "genere", "generer",
        "fabrique", "fabriquer",
        "dessine", "dessiner", "dessinez",
        "make", "create",
    )

    /** Noms qui désignent le pictogramme (canoniques, français et anglais). */
    private val PICTO_WORDS = setOf("pictogramme", "picto", "pictogram")

    /** Articles et possessifs retirés devant le label (« de ces médics »). */
    private val LEADERS = setOf(
        "de", "d", "des", "du", "le", "la", "les", "l", "un", "une",
        "ce", "cet", "cette", "ces", "mon", "ma", "mes",
    )

    /** Démonstratifs : l'objet est filmé, aucun nom à guider (« de ça »). */
    private val NAMELESS = setOf("ca", "cela")

    /**
     * Label brut de l'ordre porté par [text], `null` si ce n'en est pas un
     * (le texte part alors au LLM comme avant). Vide = objet sans nom.
     */
    fun parse(text: String): String? {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        val at = words.indexOfFirst { PICTO_WORDS.contains(it) }
        if (at < 0 || words.none { CREATE_VERBS.contains(it) }) return null
        val rest = words.drop(at + 1).dropWhile { LEADERS.contains(it) }
        if (rest.isEmpty() || (rest.size == 1 && NAMELESS.contains(rest[0]))) return ""
        return rest.joinToString(" ")
    }

    /**
     * Vrai si [spoken] dicté (un mot) vise le repère nommé [label], même à
     * libellé composé (« médicaments » → « boîte de médicaments ») :
     * égalité, singulier/pluriel simple (« clé » → « clés ») ou préfixe
     * partagé (« médicament » → « médics », l'abrégé oral). Un label vide
     * ne guide jamais. Pur et testé en JVM.
     */
    fun matches(spoken: String, label: String): Boolean {
        val word = KnownSizes.canonical(spoken)
        if (word.isEmpty() || word.contains(' ')) return false
        return KnownSizes.canonical(label).split(' ').filter { it.isNotEmpty() }.any { matchWord(word, it) }
    }

    /** Un mot parlé contre un mot de label : égalité, pluriel ou préfixe. */
    private fun matchWord(word: String, name: String): Boolean {
        if (word == name) return true
        val flip = if (word.endsWith("s")) word.dropLast(1) else word + "s"
        if (flip == name) return true
        return word.commonPrefixWith(name).length >= PREFIX_MIN &&
            minOf(word.length, name.length) >= PREFIX_LEN
    }

    /** Préfixe partagé minimal (« medic ») pour reconnaître un abrégé. */
    private const val PREFIX_MIN = 4

    /** Longueur minimale des deux côtés pour la règle du préfixe. */
    private const val PREFIX_LEN = 5
}

/**
 * Phrases parlées du picto du direct, dites par la voix des positions.
 * Pures et testées en JVM.
 */
object PictoVoiceText {

    /** La génération part (~10 s) : le dire, puis se taire jusqu'au résultat. */
    fun started(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Je crée le pictogramme."
        SpeechLanguage.En -> "Creating the pictogram."
    }

    /** Le repère est armé : le nommer et dire comment être guidé. */
    fun created(label: String, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Pictogramme $label prêt. Dis son nom pour être guidé."
        SpeechLanguage.En -> "Pictogram $label ready. Say its name for guidance."
    }

    /** Le repère sans nom (« de ça ») est armé : overlay seul, jamais guidé. */
    fun createdUnnamed(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Pictogramme prêt."
        SpeechLanguage.En -> "Pictogram ready."
    }

    /** Échec (photo, clé, réseau : le détail reste à l'écran, jamais dit). */
    fun failed(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Création du pictogramme impossible."
        SpeechLanguage.En -> "Pictogram creation failed."
    }

    /** Une génération est déjà en vol : la seconde est jetée, et dite. */
    fun busy(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Création déjà en cours."
        SpeechLanguage.En -> "Creation already running."
    }
}
