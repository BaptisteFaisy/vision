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
 * voudrais / vais aller à X », « j'aimerais aller à X », « emmène-moi à X »,
 * « naviguer vers X », « conduis-moi à X », « itinéraire vers X »,
 * « guide-moi jusqu'à / vers X », « va à X » (en début de phrase) et
 * « aller à X ». La destination rendue est le texte original, pas une forme
 * normalisée.
 *
 * Volontairement absents : « où est X » (c'est une question, pas un ordre —
 * elle reste au LLM) et « va à » en milieu de phrase (« il va à Paris » n'est
 * pas un ordre).
 *
 * Pur, sans dépendance, et ne lève jamais : texte vide ou sans intention
 * → `null`.
 */
object NavigationIntentParser {

    /** « à » sous toutes ses casses, sans dépendre du repliement Unicode. */
    private const val A = "[aàâäAÀÂÄ]"

    /** « je veux / voudrais / vais aller à X » — avant [ALLER], qui en est la queue. */
    private val JE_VEUX = Regex("""\bje\s+(?:veux|voudrais|vais)\s+aller\s+${A}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « j'aimerais aller à X ». */
    private val J_AIMERAIS = Regex("""\bj\s*['’‘`]\s*aimerais\s+aller\s+${A}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « emmène-moi à X » (et « emmenez-moi »). */
    private val EMMENE = Regex(
        """\bemm[eèéêëEÈÉÊË]nez?[\s\-'’`]+moi\s+${A}\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « naviguer vers X ». */
    private val NAVIGUER = Regex("""\bnaviguer\s+vers\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « conduis-moi à X » (et « conduisez-moi »). */
    private val CONDUIS = Regex(
        """\bconduis(?:ez)?[\s\-'’`]+moi\s+${A}\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « itinéraire vers X » (avec ou sans accent). */
    private val ITINERAIRE = Regex("""\bitin[eéEÉ]raire\s+vers\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « guide-moi jusqu'à X » / « guide-moi vers X » (et « guidez-moi »). */
    private val GUIDE = Regex(
        """\bguide(?:z)?[\s\-'’`]+moi\s+(?:jusqu['’‘`]\s*${A}\s+|vers\s+)(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /** « va à X » — ancré au début : « il va à Paris » n'est pas un ordre. */
    private val VA = Regex("""^\s*va\s+${A}\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « aller à X » — dernier : sous-phrase des formes longues. */
    private val ALLER = Regex("""\baller\s+${A}\s+(.+)""", RegexOption.IGNORE_CASE)

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
