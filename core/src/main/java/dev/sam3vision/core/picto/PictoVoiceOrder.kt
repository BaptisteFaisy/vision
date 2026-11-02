package dev.sam3vision.core.picto

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.live.PositionText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Aides vocales picto : le mot qui vise un repère armé ([matches],
 * [huntWord] — « médicament » guide vers « boîte de médicaments ») et la
 * démo overlay ([isDemoToggle], [demoHits]). Il n'y a plus aucun ordre de
 * création ou de recherche ici : l'unique ordre « mode find my X » est parsé
 * par [dev.sam3vision.core.speech.DictationOrders], tout le reste part au
 * LLM. Tolérant : casse, accents et ponctuation neutralisés par
 * [KnownSizes.canonical]. Pur et testé en JVM.
 */
object PictoVoiceOrder {

    /** Noms qui désignent le pictogramme (canoniques, français et anglais). */
    private val PICTO_WORDS = setOf("pictogramme", "picto", "pictogram")

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
     * Faux repères de la démo : un « wall » plein cadre et une « window »,
     * positions fixes — le guidage y est déterministe (« wall at 12 o'clock »).
     */
    fun demoHits(): List<PictoHit> = listOf(
        PictoHit(pictoId = "demo", label = "wall", x = 0.05f, y = 0.05f, w = 0.9f, h = 0.9f, score = 1f),
        PictoHit(pictoId = "demo", label = "window", x = 0.55f, y = 0.2f, w = 0.3f, h = 0.4f, score = 1f),
    )

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
 * Phrases parlées de la recherche picto, dites par la voix des positions.
 * Pures et testées en JVM.
 */
object PictoVoiceText {

    /** La démo change d'état : le dire, c'est le seul témoin du basculement. */
    fun demo(active: Boolean, language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> if (active) "Démo picto activée." else "Démo picto coupée."
        SpeechLanguage.En -> if (active) "Picto demo on." else "Picto demo off."
    }

    /** L'ordre « mode find my X » part : SAM suit X, son pictogramme se crée. */
    fun findStarted(label: String, language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr -> "Cherche $label."
        SpeechLanguage.En -> "Finding $label."
    }

    /** La recherche dehors : refusée, jamais de picto dehors. */
    fun refused(language: SpeechLanguage = SpeechLanguage.DEFAULT): String = when (language) {
        SpeechLanguage.Fr -> "Que dedans."
        SpeechLanguage.En -> "Indoors only."
    }

    /**
     * Le trouvé au cadran [hour] : « Mug at 9 o'clock. » — dit quand la
     * chasse voit l'objet, une fois par couple (objet, cadran).
     */
    fun found(label: String, hour: Int, language: SpeechLanguage = SpeechLanguage.DEFAULT): String {
        val name = label.trim().replaceFirstChar { it.uppercaseChar() }
        return "$name ${PositionText.hourLabel(hour, language)}."
    }
}
