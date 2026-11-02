package dev.sam3vision.core.speech

import dev.sam3vision.core.voicenav.NavClarify
import dev.sam3vision.core.voicenav.VoiceDestinationParser

/**
 * Ordre de dictée iSee (spec v4) : l'un des 11 ordres préfixés par « mode ».
 * Tout le reste d'une phrase dictée est une question pour le LLM — y compris
 * les chasses sans « mode » (« où sont mes clés », « where are my keys »,
 * « find my keys ») : l'unique ordre de recherche et de création picto est
 * « mode find my X », quel que soit l'objet cherché.
 *
 * Les 11 ordres : outdoor, indoor, crossing on, crossing off, find my X,
 * find off, GPS <destination>, GPS (seul : redemander), GPS off, glasses on,
 * glasses off.
 */
sealed interface DictationOrder {
    /** « mode outdoor » : passer en mode dehors. */
    data object Outdoor : DictationOrder

    /** « mode indoor » : passer en mode dedans. */
    data object Indoor : DictationOrder

    /** « mode crossing on » : activer le mode Traversée. */
    data object CrossingOn : DictationOrder

    /** « mode crossing off » : couper le mode Traversée. */
    data object CrossingOff : DictationOrder

    /** « mode find my X » : chercher l'objet [label] (texte brut, tout objet). */
    data class Find(val label: String) : DictationOrder

    /** « mode find off » : arrêter la recherche d'objet en cours. */
    data object FindOff : DictationOrder

    /**
     * « mode GPS … X » : guider vers [destination]. Formes FR/EN comprises
     * (« je veux aller à X », « I want to go to X »…) ou nom de lieu nu
     * (« mode GPS gare de Lyon ») — voir [DictationOrders.parse].
     */
    data class GpsGo(val destination: String) : DictationOrder

    /**
     * « mode GPS » seul ou sans destination comprise : redemander où aller
     * (« Où aller ? » / « Where to? »), jamais une devinette au LLM.
     */
    data object GpsAsk : DictationOrder

    /** « mode GPS off » : couper le guidage GPS. */
    data object GpsOff : DictationOrder

    /** « mode glasses on » : ouvrir la session lunettes. */
    data object GlassesOn : DictationOrder

    /** « mode glasses off » : fermer la session lunettes. */
    data object GlassesOff : DictationOrder
}

/**
 * Parseur des ordres de dictée : « mode » en premier mot, puis l'un des 11
 * ordres ; sans « mode », tout part au LLM ([DictationOrder]). Insensible à
 * la casse et aux espaces/ponctuation superflus ; les libellés (objet
 * cherché, destination) gardent le texte dicté tel quel, sans le « please »
 * final éventuel côté recherche et côté GPS.
 *
 * Règles strictes (spec v4) : un « mode … » qui n'est aucun des 11 ordres
 * rend `null` (question LLM, jamais un ordre deviné) ; les ordres fixes
 * n'acceptent aucun mot supplémentaire (« mode outdoor please » ⇒ `null`) ;
 * « mode find my » sans objet rend `null`. Seul « mode GPS » sans
 * destination comprise redemande où aller ([DictationOrder.GpsAsk]) au lieu
 * de partir au LLM — un GPS muet vers le LLM y répondait « pas de carte ».
 * Pur, sans dépendance Android, ne lève jamais.
 */
object DictationOrders {

    /** Préfixe obligatoire, en premier mot de la phrase dictée. */
    const val PREFIX = "mode"

    /**
     * Questions de lieu après « mode GPS » (« où est X », « comment aller à
     * X », « where is X », « how far… ») : des questions LLM, jamais des
     * destinations — le `\b` protège les lieux (« Oust », « Howard »).
     */
    private val GPS_QUESTION = Regex(
        """^\s*(?:où|ou|where|comment|how|combien|c['’‘`]\s*est\s+loin)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Ordre porté par [text] dicté, `null` si ce n'en est pas un (le texte
     * part alors au LLM comme question).
     */
    fun parse(text: String): DictationOrder? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (!isWord(words[0], PREFIX)) return null
        val rest = words.drop(1)
        if (rest.isEmpty()) return null
        val keys = rest.map { key(it) }
        return when {
            // Ordres fixes : correspondance exacte, sans mot supplémentaire.
            keys == listOf("outdoor") -> DictationOrder.Outdoor
            keys == listOf("indoor") -> DictationOrder.Indoor
            keys == listOf("crossing", "on") -> DictationOrder.CrossingOn
            keys == listOf("crossing", "off") -> DictationOrder.CrossingOff
            keys == listOf("find", "off") -> DictationOrder.FindOff
            keys == listOf("gps", "off") -> DictationOrder.GpsOff
            keys == listOf("glasses", "on") -> DictationOrder.GlassesOn
            keys == listOf("glasses", "off") -> DictationOrder.GlassesOff
            // Ordres à libellé : mot-clé puis texte brut non vide.
            keys.size >= 3 && keys[0] == "find" && keys[1] == "my" ->
                payload(dropPlease(rest), 2)?.let { DictationOrder.Find(it) }
            keys.isNotEmpty() && keys[0] == "gps" -> parseGps(rest)
            else -> null
        }
    }

    /**
     * Ordres GPS après « mode GPS » : « off » exact est déjà filtré (ordre
     * fixe) ; seul redemande ([DictationOrder.GpsAsk]) ; sinon le reste de
     * la phrase est une demande de guidage — forme FR/EN comprise
     * ([VoiceDestinationParser]), tête d'ordre sans destination ([NavClarify]
     * → [DictationOrder.GpsAsk]), ou nom de lieu nu (« mode GPS gare de
     * Lyon » → [DictationOrder.GpsGo]). Un « off » suivi d'autre chose et
     * les questions de lieu (« où est X ») restent au LLM (`null`), jamais
     * devinés. Ne lève jamais.
     */
    private fun parseGps(rest: List<String>): DictationOrder? {
        // « off » + autre chose (« mode GPS off please ») : pas l'ordre fixe.
        if (rest.size > 1 && key(rest[1]) == "off") return null
        // « mode GPS » seul (même avec un « please ») : redemander où aller.
        val words = dropPlease(rest)
        if (words.size == 1) return DictationOrder.GpsAsk
        val spoken = words.drop(1).joinToString(" ").trim()
        // Forme comprise (« je veux aller à X », « I want to go to X »…).
        runCatching { VoiceDestinationParser.parse(spoken) }.getOrNull()?.let {
            return DictationOrder.GpsGo(it)
        }
        // Tête d'ordre sans destination (« je veux aller », « I want to go »).
        if (runCatching { NavClarify.isUnclearOrder(spoken) }.getOrDefault(false)) {
            return DictationOrder.GpsAsk
        }
        // Question de lieu (« où est X », « comment aller… ») : au LLM.
        if (GPS_QUESTION.containsMatchIn(spoken)) return null
        // Nom de lieu nu (« gare de Lyon ») ; miette STT : redemander.
        val raw = spoken.trim('.', ',', '?', '!', ';', ':').trim()
        if (raw.length < 3) return DictationOrder.GpsAsk
        return DictationOrder.GpsGo(raw)
    }

    /** Vrai si [text] dicté est un ordre (consommé par le routeur). */
    fun isOrder(text: String): Boolean = parse(text) != null

    /** Mot-clé de comparaison : casse et ponctuation de bord neutralisées. */
    private fun key(word: String): String = word.trim().trim('.', ',', '?', '!', ';', ':').lowercase()

    private fun isWord(word: String, want: String): Boolean = key(word) == want

    /** [words] sans « please » final (« mode find my keys please » cherche « keys »). */
    private fun dropPlease(words: List<String>): List<String> =
        words.dropLastWhile { key(it) == "please" }

    /** Texte brut dicté à partir du mot [from] (espaces resserrés), `null` si vide. */
    private fun payload(words: List<String>, from: Int): String? {
        val raw = words.drop(from).joinToString(" ").trim().trim('.', ',', '?', '!', ';', ':').trim()
        return raw.ifEmpty { null }
    }
}
