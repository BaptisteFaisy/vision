package dev.sam3vision.core.speech

/**
 * Ordre de dictée iSee (spec v4) : l'un des 10 ordres préfixés par « mode ».
 * Tout le reste d'une phrase dictée est une question pour le LLM.
 *
 * Les 10 ordres : outdoor, indoor, crossing on, crossing off, find my X,
 * find off, GPS I want to go to X, GPS off, glasses on, glasses off.
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

    /** « mode find my X » : chercher l'objet [label] (libellé brut dicté). */
    data class Find(val label: String) : DictationOrder

    /** « mode find off » : arrêter la recherche d'objet en cours. */
    data object FindOff : DictationOrder

    /** « mode GPS I want to go to X » : guider vers [destination]. */
    data class GpsGo(val destination: String) : DictationOrder

    /** « mode GPS off » : couper le guidage GPS. */
    data object GpsOff : DictationOrder

    /** « mode glasses on » : ouvrir la session lunettes. */
    data object GlassesOn : DictationOrder

    /** « mode glasses off » : fermer la session lunettes. */
    data object GlassesOff : DictationOrder
}

/**
 * Parseur des ordres de dictée : « mode » en premier mot, puis l'un des 10
 * ordres. Insensible à la casse et aux espaces/ponctuation superflus ; les
 * libellés (objet cherché, destination) gardent le texte dicté tel quel.
 *
 * Règles strictes (spec v4) : la phrase doit COMMENCER par « mode » ; un
 * « mode … » qui n'est aucun des 10 ordres rend `null` (question LLM,
 * jamais un ordre deviné) ; les ordres fixes n'acceptent aucun mot
 * supplémentaire (« mode outdoor please » ⇒ `null`) ; les ordres à
 * libellé exigent un libellé non vide (« mode find my » ⇒ `null`).
 * Pur, sans dépendance, ne lève jamais.
 */
object DictationOrders {

    /** Préfixe obligatoire, en premier mot de la phrase dictée. */
    const val PREFIX = "mode"

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
                payload(rest, 2)?.let { DictationOrder.Find(it) }
            keys.size >= 7 && keys[0] == "gps" && keys[1] == "i" && keys[2] == "want" &&
                keys[3] == "to" && keys[4] == "go" && keys[5] == "to" ->
                payload(rest, 6)?.let { DictationOrder.GpsGo(it) }
            else -> null
        }
    }

    /** Vrai si [text] dicté est un ordre (consommé par le routeur). */
    fun isOrder(text: String): Boolean = parse(text) != null

    /** Mot-clé de comparaison : casse et ponctuation de bord neutralisées. */
    private fun key(word: String): String = word.trim().trim('.', ',', '?', '!', ';', ':').lowercase()

    private fun isWord(word: String, want: String): Boolean = key(word) == want

    /** Texte brut dicté à partir du mot [from] (espaces resserrés), `null` si vide. */
    private fun payload(words: List<String>, from: Int): String? {
        val raw = words.drop(from).joinToString(" ").trim().trim('.', ',', '?', '!', ';', ':').trim()
        return raw.ifEmpty { null }
    }
}
