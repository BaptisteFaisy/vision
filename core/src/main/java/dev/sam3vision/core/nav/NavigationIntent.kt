package dev.sam3vision.core.nav

/**
 * Intention de navigation exprimée en langage naturel.
 *
 * [destination] est la destination **brute** : texte original tel que dicté
 * (casse et accents conservés), sans la ponctuation de fin de phrase.
 */
data class NavigationIntent(val destination: String)

/**
 * Parseur pur d'intention de navigation (français oral).
 *
 * Formes reconnues (insensibles à la casse et aux accents) : « je veux /
 * voudrais / vais aller … X », « j'aimerais aller … X », « emmène/amène-moi
 * … X », « naviguer vers X », « conduis-moi … X », « itinéraire vers X »,
 * « guide-moi jusqu'à/au/aux / vers X », « va … X » (en début de phrase) et
 * « aller … X » — « … » vaut [PREP] (à, au, aux, en, dans, vers, chez,
 * jusqu'à). La destination rendue est le texte original, pas une forme
 * normalisée.
 *
 * Volontairement absents : « où est X » (c'est une question, pas un ordre —
 * elle reste au LLM), « va … » en milieu de phrase (« il va à Paris » n'est
 * pas un ordre) et les expressions figées (« en savoir plus », « dans le
 * détail », « en avant/arrière/haut/bas » : pas des destinations).
 *
 * Pur, sans dépendance, et ne lève jamais : texte vide ou sans intention
 * → `null`.
 */
object NavigationIntentParser {

    /** « à » sous toutes ses casses, sans dépendre du repliement Unicode. */
    private const val A = "[aàâäAÀÂÄ]"

    /** « jusqu'à/au/aux » : direction + préposition contractée ou non. */
    private const val JUSQU = """jusqu['’‘`]\s*(?:aux|au|$A)"""

    /**
     * Prépositions de destination du français oral : « à » et ses formes
     * contractées (« au », « aux »), « en » (« en ville »), « dans », « vers »
     * et « chez » (« chez le médecin »). Ordre longest-first pour un
     * backtracking sûr ; « en » refuse les directions relatives et
     * l'expression figée (« en savoir plus » n'est pas une destination),
     * « dans » refuse « dans le détail ».
     */
    private const val PREP = """(?:$JUSQU|aux|au|en(?!\s+(?:savoir|avant|arri[eè]re|haut|bas)\b)|dans(?!\s+les?\s+d[eé]tails?\b)|vers|chez|$A)"""

    /** « je veux / voudrais / vais aller … X » ([PREP]) — avant [ALLER], qui en est la queue. */
    private val JE_VEUX = Regex("""\bje\s+(?:veux|voudrais|vais)\s+aller\s+${PREP}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « j'aimerais aller … X » ([PREP]). */
    private val J_AIMERAIS = Regex("""\bj\s*['’‘`]\s*aimerais\s+aller\s+${PREP}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « emmène/amène-moi … X » ([PREP], et « emmenez/amenez-moi » — variantes STT). */
    private val EMMENE = Regex(
        """\b(?:emm|am)[eèéêëEÈÉÊË]nez?[\s\-'’`]+moi\s+${PREP}\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « naviguer vers X ». */
    private val NAVIGUER = Regex("""\bnaviguer\s+vers\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « conduis-moi … X » ([PREP], et « conduisez-moi »). */
    private val CONDUIS = Regex(
        """\bconduis(?:ez)?[\s\-'’`]+moi\s+${PREP}\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « itinéraire vers X » (avec ou sans accent). */
    private val ITINERAIRE = Regex("""\bitin[eéEÉ]raire\s+vers\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « guide-moi jusqu'à/au/aux X » / « guide-moi vers X » (et « guidez-moi »). */
    private val GUIDE = Regex(
        """\bguide(?:z)?[\s\-'’`]+moi\s+(?:${JUSQU}\s+|vers\s+)(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « va … X » ([PREP]) — ancré au début : « il va à Paris » n'est pas un ordre. */
    private val VA = Regex("""^\s*va\s+${PREP}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « aller … X » ([PREP]) — dernier : sous-phrase des formes longues. */
    private val ALLER = Regex("""\baller\s+${PREP}\s+(.+)""", RegexOption.IGNORE_CASE)

    private val PATTERNS = listOf(JE_VEUX, J_AIMERAIS, EMMENE, NAVIGUER, CONDUIS, ITINERAIRE, GUIDE, VA, ALLER)

    /**
     * Intention contenue dans [text], ou `null` si aucune forme connue
     * n'est reconnue ou si la destination est vide.
     */
    fun parse(text: String): NavigationIntent? {
        if (text.isBlank()) return null
        for (pattern in PATTERNS) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val destination = clean(raw)
            if (destination.isNotEmpty()) return NavigationIntent(destination)
        }
        return null
    }

    /** Destination brute : texte original rogné, ponctuation finale retirée. */
    private fun clean(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', '…').trim()
}
