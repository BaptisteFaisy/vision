package dev.sam3vision.core.voicenav

import dev.sam3vision.core.nav.NavigationIntentParser

/**
 * Destination dictée en français oral → adresse brute, ou `null`.
 *
 * Délègue d'abord à [NavigationIntentParser] (« je veux aller à X »,
 * « emmène-moi à X », « naviguer vers X »…), puis couvre les formes courtes
 * que la consigne vocale utilise et que lui ne connaît pas :
 *
 * - « direction X » (« direction gare de Lyon »), en début de dictée, avec un
 *   « s'il te/vous plaît » initial optionnel ;
 * - « emmène-moi X » quand la STT a mangé le « à » (« emmène-moi gare de Lyon »).
 *
 * « direction » est volontairement ancré au début : en milieu de phrase c'est
 * un nom commun (« quelle est la direction à suivre ? » est une question, pas
 * un ordre). Comme [NavigationIntentParser], la destination rendue est le texte
 * original (casse et accents conservés), sans la ponctuation de fin de phrase.
 *
 * Pur (JVM seule, sans Android ni coroutine) et ne lève jamais : texte vide,
 * blanc, question ou sans intention → `null`.
 */
object VoiceDestinationParser {

    /** « s'il te plaît, … » initial, optionnel et sans capture. */
    private const val POLITESSE = """(?:s['’‘`]il\s+(?:te|vous)\s+pla[iî]t\s*,?\s*)?"""

    /** « direction X » — ancré au début (voir KDoc). */
    private val DIRECTION =
        Regex("""^\s*$POLITESSE""" + """directions?\s+(.+)""", RegexOption.IGNORE_CASE)

    /**
     * « de » résiduel : « direction de la gare » → « la gare ». Le « de » seul
     * ne part que devant un article (« de Gaulle » se garde tel quel) ; les
     * formes élidées ou contractées (« d'Orly », « du Panthéon », « des Halles »)
     * perdent leur préposition.
     */
    private val DE_RESIDUEL = Regex(
        """^(?:de\s+(?=(?:la|le|les|un|une)\s|l['’])|d['’]\s*|du\s+|des\s+)(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * « emmène-moi X » sans « à » (et « emmenez-moi »). La forme avec « à »
     * est déjà rendue par [NavigationIntentParser], essayé avant.
     */
    private val EMMENE_SANS_A = Regex(
        """\bemm[eèéêëEÈÉÊË]nez?[\s\-'’`]+moi\s+(.+)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Destination brute contenue dans [transcript], ou `null` si aucune forme
     * connue n'est reconnue ou si la destination est vide.
     */
    fun parse(transcript: String): String? {
        if (transcript.isBlank()) return null
        NavigationIntentParser.parse(transcript)?.let { return it.destination }
        direction(transcript)?.let { return it }
        return emmeneSansA(transcript)
    }

    private fun direction(text: String): String? {
        val raw = DIRECTION.find(text)?.groupValues?.getOrNull(1) ?: return null
        val sansDe = DE_RESIDUEL.find(raw.trim())?.groupValues?.getOrNull(1) ?: raw
        return clean(sansDe).ifEmpty { null }
    }

    private fun emmeneSansA(text: String): String? {
        val raw = EMMENE_SANS_A.find(text)?.groupValues?.getOrNull(1) ?: return null
        val destination = clean(raw)
        // « emmène-moi à » sans suite : il ne reste que la préposition.
        if (destination.equals("à", ignoreCase = true)) return null
        if (destination.equals("a", ignoreCase = true)) return null
        return destination.ifEmpty { null }
    }

    /** Même règle que [NavigationIntentParser] : original rogné, ponctuation finale retirée. */
    private fun clean(raw: String): String =
        raw.trim().trimEnd('.', '!', '?', '…').trim()
}
