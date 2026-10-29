package dev.sam3vision.core.nav

/**
 * Réponse vocale à la question de confirmation (« Do you really want to go
 * to X? … Answer yes or no. ») : anglais et français acceptés.
 */
enum class ConfirmAnswer { YES, NO }

/**
 * Détecteur déterministe de la réponse oui/non (un seul mot, insensible à
 * la casse et aux accents) : « yes », « yeah », « yep », « oui » → [YES] ;
 * « no », « nope », « non » → [NO]. Toute autre forme (`null`) — phrase
 * entière, politesse (« yes please »), destination (« Notre-Dame ») — n'est
 * pas une réponse et suit le routage normal (nouvelle destination, LLM…).
 *
 * Le mot seul suffit ici (contrairement à [StopGuidanceParser] qui exige un
 * nom) : la question posée n'attend que ça, et hors confirmation ce parseur
 * n'est jamais consulté — aucun risque de couper autre chose.
 *
 * Comme [StopGuidanceParser] : pur, sans dépendance, ne lève jamais.
 */
object ConfirmAnswerParser {

    private val YES_WORDS = setOf("yes", "yeah", "yep", "oui")

    private val NO_WORDS = setOf("no", "nope", "non")

    /** [ConfirmAnswer] si [text] est un oui/non isolé, sinon `null`. */
    fun parse(text: String): ConfirmAnswer? {
        if (text.isBlank()) return null
        val words = foldAccents(text).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        if (words.size != 1) return null
        val word = words[0]
        return when {
            word in YES_WORDS -> ConfirmAnswer.YES
            word in NO_WORDS -> ConfirmAnswer.NO
            else -> null
        }
    }
}

private val FOLD_ACCENTS = mapOf(
    'à' to 'a', 'â' to 'a', 'ä' to 'a',
    'é' to 'e', 'è' to 'e', 'ê' to 'e', 'ë' to 'e',
    'î' to 'i', 'ï' to 'i', 'ô' to 'o', 'ö' to 'o',
    'ù' to 'u', 'û' to 'u', 'ü' to 'u', 'ÿ' to 'y',
    'ç' to 'c', 'œ' to 'o', 'æ' to 'a',
)

/**
 * Minuscules + accents repliés, pour comparer sans dépendre d'Unicode.
 * Partagé par les parseurs vocaux ([ConfirmAnswerParser], [StopGuidanceParser]).
 */
internal fun foldAccents(text: String): String =
    text.lowercase().map { FOLD_ACCENTS[it] ?: it }.joinToString("")
