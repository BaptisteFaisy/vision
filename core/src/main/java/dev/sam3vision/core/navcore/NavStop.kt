package dev.sam3vision.core.navcore

/**
 * Détecteur pur de l'ordre STOP (« arrête / stop / annule le guidage »).
 *
 * Complète [dev.sam3vision.core.nav.NavigationIntentParser] (ordres GO,
 * intouchés) : l'ordre STOP est consommé AVANT le LLM, comme la traversée
 * (spéc. § a.4). Insensible à la casse et à l'accent de « arrête ». Pur, sans
 * dépendance, ne lève jamais : texte vide ou sans ordre → `false`.
 */
object NavStopParser {

    private val STOP = Regex(
        """\b(arr[eéêè]te|stop(?:pe)?|annule)\s+(?:le|la)\s+(guidage|navigation)\b""",
        RegexOption.IGNORE_CASE,
    )

    /** `true` si [text] contient un ordre d'arrêt du guidage. */
    fun isStop(text: String): Boolean {
        if (text.isBlank()) return false
        return STOP.containsMatchIn(text)
    }
}
