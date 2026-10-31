package dev.sam3vision.app.glasses.bench

/**
 * Journal et messages d'erreur du spike, sans Android.
 *
 * La politique d'affichage — combien de lignes garder, comment raccourcir une
 * erreur — est ici, donc testée sur JVM (`SpikeLogTest`) ; les appelants
 * (`MainActivity`, `GlassesVoice`) ne font que l'exécuter.
 */

/** Nombre de lignes de journal conservées à l'écran. */
internal const val SPIKE_LOG_LINES = 60

/** Longueur maximale d'un message d'erreur affiché. */
internal const val SHORT_ERROR_CHARS = 120

/** Repli quand une erreur ne porte aucun message exploitable. */
internal const val FALLBACK_ERROR_MESSAGE = "synthesis impossible"

/**
 * Ajoute [line] à la fin de [lines] et ne garde que les [maxLines] dernières :
 * l'écran du spike montre la fin du journal, pas son début.
 */
internal fun appendLogLine(
    lines: List<String>,
    line: String,
    maxLines: Int = SPIKE_LOG_LINES,
): List<String> {
    if (maxLines <= 0) return emptyList()
    return (lines + line).takeLast(maxLines)
}

/** Message court, non vide et borné, pour l'UI ; repli si l'erreur n'en a pas. */
internal fun shortErrorMessage(
    error: Throwable,
    maxChars: Int = SHORT_ERROR_CHARS,
): String = error.message?.take(maxChars)?.takeIf { it.isNotBlank() } ?: FALLBACK_ERROR_MESSAGE
