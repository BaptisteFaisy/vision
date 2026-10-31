package dev.sam3vision.core.nav

import dev.sam3vision.core.distance.KnownSizes

/**
 * Intention de navigation exprimée en langage naturel.
 *
 * [destination] est la destination **brute** : texte original tel que dicté
 * (casse et accents conservés), sans la ponctuation de fin de phrase.
 */
data class NavigationIntent(val destination: String)

/**
 * Parseur pur d'intention de navigation (français et anglais oraux).
 *
 * Formes reconnues (insensibles à la casse et aux accents) : « je veux /
 * voudrais / vais aller … X », « j'aimerais aller … X », « emmène/amène-moi
 * … X », « naviguer vers X », « conduis-moi … X », « itinéraire vers X »,
 * « guide-moi jusqu'à/au/aux / vers X », « va … X » (en début de phrase) et
 * « aller … X » — « … » vaut [PREP] (à, au, aux, en, dans, vers, chez,
 * jusqu'à). La destination rendue est le texte original, pas une forme
 * normalisée.
 *
 * Formes anglaises : « I want/wanna/would like to go to X », « take/bring me
 * to X » (et sans « to », STT qui mange la préposition), « navigate to X »,
 * « guide me to X », « (I'm) going to X », « let's go to X », « go to X » et
 * « go X » (ancrés au début), « directions to X » (ancré). Mêmes gardes :
 * compléments non-lieux refusés (« go fast », « take me back »), « to » en
 * tête renvoyé aux motifs stricts.
 *
 * Quatre formes orales de plus, ajoutées après une dictée réelle où la
 * phrase est partie au LLM (« décris la pièce ») faute d'être comprise :
 *
 * - « je veux aller X » **sans préposition** (« je veux aller gare
 *   Montparnasse ») — la transcription mange souvent le « à » ;
 * - « aller X » / « va X » nus, ancrés au début (« il va aller vite » n'est
 *   pas un ordre) ;
 * - « aller au / aux / vers X » : la préposition ne fait pas partie du nom ;
 * - les compléments qui ne sont pas des lieux (« aller vite », « aller
 *   chercher », « aller voir »…) sont refusés.
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

    /** « je veux / voudrais / vais aller X » sans « à » (STT qui mange la préposition). */
    private val JE_VEUX_SANS_A = Regex(
        """\bje\s+(?:veux|voudrais|vais)\s+aller\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

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

    /** « aller X » nu, ancré au début (« aller gare Montparnasse »). */
    private val ALLER_SANS_A = Regex("""^\s*aller\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « va X » nu, ancré au début (« va gare Montparnasse ») — « ça va » passe à côté. */
    private val VA_SANS_A = Regex("""^\s*va\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « I want/wanna/would like to go to X ». */
    private val I_WANT_EN = Regex("""\bi\s+(?:want\s+to|wanna|would\s+like\s+to)\s+go\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « I'd like to go to X » (avec ou sans apostrophe). */
    private val I_D_LIKE = Regex("""\bi\s*['’‘`]?d\s+like\s+to\s+go\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « I want/wanna/would like to go X » sans « to » (STT qui mange la préposition). */
    private val I_WANT_SANS_TO = Regex("""\bi\s+(?:want\s+to|wanna|would\s+like\s+to)\s+go\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « take/bring me to X ». */
    private val TAKE_ME = Regex("""\b(?:take|bring)\s+me\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « take/bring me X » sans « to ». */
    private val TAKE_ME_SANS_TO = Regex("""\b(?:take|bring)\s+me\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « navigate to X ». */
    private val NAVIGATE_EN = Regex("""\bnavigate\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « guide me to X ». */
    private val GUIDE_ME_EN = Regex("""\bguide\s+me\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « going to X » (« I'm going to X » : le « I'm » ne gêne pas). */
    private val GOING_TO = Regex("""\bgoing\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « let's go to X » (avec ou sans apostrophe). */
    private val LETS_GO = Regex("""\blet['’‘`]?s\s+go\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « go to X » — ancré au début. */
    private val GO_TO = Regex("""^\s*go\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « go X » nu, ancré au début (« go Eiffel Tower »). */
    private val GO_SANS_TO = Regex("""^\s*go\s+(.+)""", RegexOption.IGNORE_CASE)

    /** « directions to X » — ancré au début, comme « direction X ». */
    private val DIRECTIONS_TO = Regex("""^\s*directions?\s+to\s+(.+)""", RegexOption.IGNORE_CASE)

    /**
     * « to » en tête du texte capturé : les formes anglaises sans préposition
     * ne valent que quand la STT l'a mangée (miroir de [LEADING_PREP]).
     */
    private val LEADING_TO = Regex("""^\s*to\s+""", RegexOption.IGNORE_CASE)

    /** Préposition en tête de destination (« au », « vers »…) : elle ne fait pas partie du nom. */
    private val PREPOSITION = Regex("""^(?:au|aux|vers)\s+(.+)""", RegexOption.IGNORE_CASE)

    /**
     * Préposition de destination en tête du texte capturé : les formes sans
     * préposition ne valent que quand la STT l'a mangée. Si elle est là
     * (« aller dans le détail », « va en arrière »), ce sont les motifs
     * stricts — et leurs exclusions — qui décident.
     */
    private val LEADING_PREP = Regex(
        """^\s*(?:$JUSQU|aux|au|en|dans|vers|chez|$A)\s+""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Compléments qui suivent « aller » ou « go » sans être des lieux :
     * jamais au géocodeur. Clés canoniques ([KnownSizes.canonical]).
     */
    private val NON_LIEUX = setOf(
        "vite", "bien", "mieux", "voir", "chercher", "dormir", "manger", "boire",
        "faire", "dire", "plus", "moins", "tout", "droit", "loin", "chez",
        "avec", "sans", "en", "y", "par", "pour", "sur", "sous",
        "fast", "faster", "quickly", "slowly", "straight", "back", "there",
        "see", "get", "fetch",
    )

    private val PATTERNS = listOf(
        JE_VEUX, JE_VEUX_SANS_A, J_AIMERAIS, EMMENE, NAVIGUER, CONDUIS,
        ITINERAIRE, GUIDE, VA, ALLER, ALLER_SANS_A, VA_SANS_A,
        I_WANT_EN, I_D_LIKE, I_WANT_SANS_TO, TAKE_ME, TAKE_ME_SANS_TO,
        NAVIGATE_EN, GUIDE_ME_EN, GOING_TO, LETS_GO, GO_TO, GO_SANS_TO, DIRECTIONS_TO,
    )

    /**
     * Intention contenue dans [text], ou `null` si aucune forme connue
     * n'est reconnue ou si la destination est vide.
     */
    fun parse(text: String): NavigationIntent? {
        if (text.isBlank()) return null
        for (pattern in PATTERNS) {
            val raw = pattern.find(text)?.groupValues?.getOrNull(1) ?: continue
            val destination = stripPreposition(clean(raw))
            if (destination.isEmpty()) continue
            // Les formes sans préposition attrapent large : un complément qui
            // n'est pas un lieu (« aller vite », « go fast ») laisse essayer les suivantes.
            if (pattern === JE_VEUX_SANS_A || pattern === ALLER_SANS_A || pattern === VA_SANS_A) {
                if (LEADING_PREP.containsMatchIn(raw)) continue
                if (!looksLikePlace(destination)) continue
            }
            if (pattern === I_WANT_SANS_TO || pattern === TAKE_ME_SANS_TO || pattern === GO_SANS_TO) {
                if (LEADING_TO.containsMatchIn(raw)) continue
                if (!looksLikePlace(destination)) continue
            }
            return NavigationIntent(destination)
        }
        return null
    }

    /** « au marché » → « marché » : la préposition sort de la requête BAN. */
    private fun stripPreposition(text: String): String =
        PREPOSITION.find(text)?.groupValues?.getOrNull(1)?.trim() ?: text

    /** Vrai si [destination] ressemble à un lieu et non à un complément d'action. */
    private fun looksLikePlace(destination: String): Boolean {
        if (destination.length < 3) return false
        val first = KnownSizes.canonical(destination).split(' ').firstOrNull().orEmpty()
        return first.isNotEmpty() && first !in NON_LIEUX
    }

    /** Destination brute : texte original rogné, ponctuation finale retirée. */
    private fun clean(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', '…').trim()
}
