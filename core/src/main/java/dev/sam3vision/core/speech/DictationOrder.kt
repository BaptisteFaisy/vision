package dev.sam3vision.core.speech

import dev.sam3vision.core.distance.KnownSizes
import dev.sam3vision.core.picto.FindIndoorTarget
import dev.sam3vision.core.picto.PictoVoiceOrder

/**
 * Ordre de dictée iSee (spec v4) : l'un des 10 ordres préfixés par « mode »,
 * ou une chasse sans « mode » visant l'une des 10 cibles dedans (« où sont
 * mes clés », « where are my keys », « find my keys » — décision user du
 * 01/11, la chasse picto d'avant-v4). Tout le reste d'une phrase dictée
 * est une question pour le LLM.
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

    /** « mode find my X » ou chasse sans « mode » : chercher l'objet [label] (concept brut). */
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
 * ordres ; sans « mode », seule une chasse visant l'une des 10 cibles
 * dedans est un ordre ([findWithoutMode]). Insensible à la casse et aux
 * espaces/ponctuation superflus ; les libellés (objet cherché,
 * destination) gardent le texte dicté tel quel.
 *
 * Règles strictes (spec v4) : un « mode … » qui n'est aucun des 10 ordres
 * « mode … » qui n'est aucun des 10 ordres rend `null` (question LLM,
 * jamais un ordre deviné) ; les ordres fixes n'acceptent aucun mot
 * supplémentaire (« mode outdoor please » ⇒ `null`) ; les ordres à
 * libellé exigent un libellé non vide (« mode find my » ⇒ `null`).
 * Pur, sans dépendance, ne lève jamais.
 */
object DictationOrders {

    /** Préfixe obligatoire, en premier mot de la phrase dictée. */
    const val PREFIX = "mode"

    /** Auxiliaires acceptes par la reprise pronominale (present + imparfait). */
    private val OU_PRONOUN_BE = setOf("est", "sont", "etait", "etaient")

    /** Pronoms repris par la chasse en cours ("ou etaient-elles ?"). */
    private val OU_PRONOUNS = setOf("elle", "elles", "il", "ils", "le", "la", "les", "lui", "ca", "cela")

    /**
     * Ordre porté par [text] dicté, `null` si ce n'en est pas un (le texte
     * part alors au LLM comme question).
     */
    fun parse(text: String, hunting: String? = null): DictationOrder? {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        if (!isWord(words[0], PREFIX)) return findWithoutMode(text, hunting)
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

    /**
     * Chasse sans « mode » : « où est/sont X », « where is/are X » ou
     * « find/locate X » visant l'une des 10 cibles dedans ([FindIndoorTarget])
     * — le concept canonique part en chasse (picto créé à sa première
     * détection, comme « mode find my X »). Toute autre question (`null` :
     * hors des 10, « where is the exit », « où est la gare ») part au LLM.
     */
    private fun findWithoutMode(text: String, hunting: String?): DictationOrder? {
        val label = PictoVoiceOrder.parseOuSont(text)
            ?: PictoVoiceOrder.parseWhere(text)
            ?: PictoVoiceOrder.parseFind(text)
        if (label != null) {
            val target = FindIndoorTarget.fromSpoken(label) ?: return null
            return DictationOrder.Find(target.concept)
        }
        return pronounHunt(text, hunting)
    }

    /**
     * Reprise pronominale ("ou etaient-elles ?") : quand une chasse est en
     * cours ([hunting], le label chasse), le pronom la reprend au lieu de
     * partir au LLM. Sans chasse : `null` (question LLM comme avant).
     */
    private fun pronounHunt(text: String, hunting: String?): DictationOrder? {
        if (hunting.isNullOrBlank()) return null
        val words = KnownSizes.canonical(text).split(' ').filter { it.isNotEmpty() }
        if (words.size < 3 || words[0] != "ou") return null
        if (!OU_PRONOUN_BE.contains(words[1])) return null
        val rest = words.drop(2).dropLastWhile { it == "please" || it == "plait" }
        if (rest.size != 1 || !OU_PRONOUNS.contains(rest[0])) return null
        val target = FindIndoorTarget.fromSpoken(hunting) ?: return null
        return DictationOrder.Find(target.concept)
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
