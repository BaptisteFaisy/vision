package dev.sam3vision.core.voicenav

import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Ordre de navigation incompris : la dictée commence comme un ordre (« je
 * veux aller », « emmène-moi », « naviguer »…) mais ni
 * [VoiceDestinationParser] ni [dev.sam3vision.core.nav.NavigationIntentParser]
 * n'en tirent une destination (préposition sans suite, destination
 * avalée par la STT, hésitation).
 *
 * Sans ce filet, ces dictées tombent au LLM qui décrit la scène — le
 * symptôme « je dis que je veux aller quelque part, il me décrit ce
 * qu'il voit ». Avec lui, l'appelant demande la destination au lieu de
 * décrire ([NavClarifyText.ask]), et l'ordre reste consommé.
 *
 * Les têtes miroirs des motifs du parseur (mêmes verbes, sans exiger la
 * destination) ; les exclusions miroirs de ses expressions figées (« en
 * savoir plus », « dans le détail » restent des questions LLM). Les
 * questions (« où est X », « où aller ? ») ne portent aucune tête et
 * restent au LLM.
 *
 * Pur (JVM seule, sans Android ni coroutine) et ne lève jamais.
 */
object NavClarify {

    /** « je veux/voudrais/vais/dois aller », « j'aimerais aller ». */
    private val JE_ALLER = Regex(
        """\bje\s+(?:veux|voudrais|vais|dois)\s+aller\b|\bj\s*['’‘`]\s*aimerais\s+aller\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « emmène/amène-moi » (variantes STT, avec ou sans suite). */
    private val MENE_MOI = Regex(
        """\b(?:emm|am)[eèéêëEÈÉÊË]nez?[\s\-'’`]+moi\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « naviguer » (avec ou sans « vers X »). */
    private val NAVIGUER = Regex("""\bnaviguer\b""", RegexOption.IGNORE_CASE)

    /** « conduis/conduisez-moi » (avec ou sans suite). */
    private val CONDUIS_MOI = Regex(
        """\bconduis(?:ez)?[\s\-'’`]+moi\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « itinéraire vers » (avec ou sans X). */
    private val ITINERAIRE = Regex(
        """\bitin[eéEÉ]raire\s+vers\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « guide/guidez-moi » (avec ou sans suite). */
    private val GUIDE_MOI = Regex(
        """\bguide(?:z)?[\s\-'’`]+moi\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « va … » ancré au début, préposition présente mais X manquant/garbled. */
    private val VA_PREP = Regex(
        """^\s*va\s+(?:aux|au|en|dans|vers|chez|[aàâäAÀÂÄ])\b""",
        RegexOption.IGNORE_CASE,
    )

    /** « aller » impératif en début de dictée (« aller », « aller à »). */
    private val ALLER_SEUL = Regex("""^\s*aller\b""", RegexOption.IGNORE_CASE)

    /** « direction » ancré au début (politesse initiale optionnelle). */
    private val DIRECTION = Regex(
        """^\s*(?:s['’‘`]il\s+(?:te|vous)\s+pla[iî]t\s*,?\s*)?directions?\b""",
        RegexOption.IGNORE_CASE,
    )

    /** Expressions figées : des questions, jamais des ordres incompris. */
    private val PAS_UN_ORDRE = Regex(
        """\ben\s+savoir\b|\bdans\s+les?\s+d[eé]tails?\b""",
        RegexOption.IGNORE_CASE,
    )

    private val TETES = listOf(
        JE_ALLER, MENE_MOI, NAVIGUER, CONDUIS_MOI, ITINERAIRE,
        GUIDE_MOI, VA_PREP, ALLER_SEUL, DIRECTION,
    )

    /**
     * Vrai si [text] est un ordre de navigation sans destination comprise :
     * tête d'ordre présente, expressions figées absentes, et aucun parseur
     * (ni [VoiceDestinationParser], ni le routeur en amont) n'a rendu une
     * destination. Un ordre compris rend `false` (il est déjà parti).
     */
    fun isUnclearOrder(text: String): Boolean {
        if (text.isBlank()) return false
        if (PAS_UN_ORDRE.containsMatchIn(text)) return false
        if (runCatching { VoiceDestinationParser.parse(text) }.getOrNull() != null) return false
        return TETES.any { runCatching { it.containsMatchIn(text) }.getOrDefault(false) }
    }
}

/**
 * Demande de destination, dans la langue de la voix ([SpeechLanguage]) :
 * redonne un exemple d'ordre complet pour réparer la dictée d'après.
 * Pure et testée en JVM.
 */
object NavClarifyText {

    /** « Je n'ai pas compris où tu veux aller… » (jamais blanche). */
    fun ask(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
        SpeechLanguage.Fr ->
            "Je n'ai pas compris où tu veux aller. Dis par exemple : je veux aller à la gare."
        SpeechLanguage.En ->
            "I didn't catch where you want to go. Say for example: I want to go to the train station."
    }
}
