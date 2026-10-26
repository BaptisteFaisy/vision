package dev.sam3vision.app.cleanup

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Journal silencieux du nettoyage : une ligne par passe dans `filesDir/cleanup.log`
 * (stockage privé) + logcat. Aucun toast, aucun son : l'utilisateur n'est jamais interrompu.
 * Le fichier est borné aux [MAX_LINES] dernières lignes pour ne pas grossir sans fin —
 * et il ne fait pas partie du périmètre purgé (ce n'est pas une capture).
 */
object CleanupLog {

    const val TAG = "CaptureCleanup"
    const val FILE_NAME = "cleanup.log"
    const val MAX_LINES = 200

    /** Ajoute [report] au journal, horodaté. Ne lève jamais. */
    fun append(context: Context, report: CleanupReport, atMs: Long = System.currentTimeMillis()) {
        val line = "$atMs ${report.logLine()}"
        Log.i(TAG, line)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            val kept = (readLines(file) + line).takeLast(MAX_LINES)
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    /** Dernières lignes du journal (test instrumenté, diagnostic). */
    fun readLines(context: Context): List<String> = readLines(File(context.filesDir, FILE_NAME))

    private fun readLines(file: File): List<String> =
        runCatching { file.takeIf { it.isFile }?.readLines().orEmpty() }.getOrDefault(emptyList())
}
