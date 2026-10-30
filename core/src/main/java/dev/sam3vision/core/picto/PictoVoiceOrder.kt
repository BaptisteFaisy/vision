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

    /** Verbes de l'ordre « Find the X » : anglais seul, en premier mot. */
    private val FIND_VERBS = setOf("find", "locate")

    /** Articles et possessifs retirés devant le label (« de ces médics »). */
    private val LEADERS = setOf(
        "de", "d", "des", "du", "le", "la", "les", "l", "un", "une",
        "ce", "cet", "cette", "ces", "mon", "ma", "mes",
    )

    /** Articles élidés après le verbe de recherche (anglais + [LEADERS]). */
    private val FIND_LEADERS = LEADERS + setOf("the", "a", "an", "my", "some")

    /** Démonstratifs : l'objet est filmé, aucun nom à guider (« de ça »). */
    private val NAMELESS = setOf("ca", "cela")

    /**
     * Vrai si [text] bascule la démo picto (« démo picto », « active la démo
     * pictogramme ») : des faux repères fixes remplacent le repérage, pour
     * tester l'overlay et le guidage sans génération ni caméra ciblée.
     */
    fun isDemoToggle(text: String): Boolean {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }.toSet()
        return words.contains("demo") && PICTO_WORDS.any { words.contains(it) }
    }

    /**
     * Faux repères de la démo : un « mur » plein cadre et une « fenêtre »,
     * positions fixes — le guidage y est déterministe (« Mur à 12 heures »).
     */
    fun demoHits(): List<PictoHit> = listOf(
        PictoHit(pictoId = "demo", label = "mur", x = 0.05f, y = 0.05f, w = 0.9f, h = 0.9f, score = 1f),
        PictoHit(pictoId = "demo", label = "fenêtre", x = 0.55f, y = 0.2f, w = 0.3f, h = 0.4f, score = 1f),
    )

    /**
     * Label brut de l'ordre « Find the X » porté par [text], `null` si ce
     * n'en est pas un. Le verbe anglais est en premier mot (« Find the keys »
     * → « keys », « Locate my phone » → « phone ») ; un « please » final est
     * ignoré. Le label sert tel quel de concept SAM et de nom de repère —
     * jamais vide (verbe seul ⇒ `null`), jamais traduit.
     */
    fun parseFind(text: String): String? {
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty() || !FIND_VERBS.contains(words[0])) return null
        val rest = words.drop(1).dropWhile { FIND_LEADERS.contains(it) }.dropLastWhile { it == "please" }
        if (rest.isEmpty()) return null
        return rest.joinToString(" ")
    }

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

    /**
     * Mot de [text] visant un label de [labels], `null` si aucun : la chasse
     * picto, partagée par le direct et l'appui long iSee. Pur et testé en JVM.
     */
    fun huntWord(text: String, labels: List<String>): String? {
        if (labels.isEmpty()) return null
        return KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
            .firstOrNull { word -> labels.any { matches(word, it) } }
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

    /** La démo change d'état : le dire, c'est le seul témoin du basculement. */
    fun demo(active: Boolean, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> if (active) "Démo picto activée." else "Démo picto coupée."
        SpeechLanguage.En -> if (active) "Picto demo on." else "Picto demo off."
    }

    /** L'ordre « Find the X » part : SAM suit X, son pictogramme se crée. */
    fun findStarted(label: String, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Je cherche $label."
        SpeechLanguage.En -> "Looking for $label."
    }
}
