package dev.sam3vision.app.cleanup

import java.io.File

/** Bilan d'une passe : ce qui est journalisé, et ce que les tests vérifient. */
data class CleanupReport(
    val filesDeleted: Int = 0,
    val filesFailed: Int = 0,
    val skippedInProgress: Int = 0,
    val tracedDeleted: Int = 0,
    val tracedLeft: Int = 0,
) {
    val totalDeleted: Int get() = filesDeleted + tracedDeleted

    /** Une ligne, lisible, pour `cleanup.log` et logcat. */
    fun logLine(): String =
        "cleanup: fichiers supprimés=$filesDeleted échecs=$filesFailed reportés=$skippedInProgress " +
            "traces MediaStore supprimées=$tracedDeleted restantes=$tracedLeft"
}

/** Bilan partiel interne (évite une classe publique de plus). */
private data class FileOutcome(val deleted: Int, val failed: Int, val skipped: Int = 0)

private data class TraceOutcome(val deleted: Int, val left: Int)

/**
 * Une passe de purge. Périmètre = [roots] (stockage privé de l'app uniquement) + les
 * seules traces MediaStore que l'app a créées. **Aucun son, aucune boîte de dialogue** :
 * le résultat part dans un log.
 *
 * Idempotence : un fichier déjà disparu est compté comme supprimé, une trace dont l'entrée
 * MediaStore n'existe plus est oubliée sans erreur. Un fichier modifié il y a moins de 60 s
 * est reporté à la passe suivante.
 */
class CaptureCleaner(
    private val roots: List<File>,
    private val store: TracedCaptureStore? = null,
    private val config: CleanupConfig = CleanupConfig.Default,
    private val now: () -> Long = System::currentTimeMillis,
) {

    suspend fun runOnce(): CleanupReport {
        val files = deleteFiles()
        val traces = deleteTraces()
        return CleanupReport(files.deleted, files.failed, files.skipped, traces.deleted, traces.left)
    }

    /** Purge récursive du périmètre privé, avec les compteurs demandés. */
    private fun deleteFiles(): FileOutcome {
        val at = now()
        var deleted = 0
        var failed = 0
        var skipped = 0
        CaptureScanner.scan(roots).forEach { file ->
            when {
                !CaptureScanner.isKindEnabled(file, config) -> Unit
                CaptureScanner.isBeingWritten(file, config, at) -> skipped++
                CaptureScanner.isDue(file, config, at) -> if (removeFile(file)) deleted++ else failed++
            }
        }
        return FileOutcome(deleted, failed, skipped)
    }

    /**
     * Suppression tolérante : un fichier déjà absent est un succès (l'utilisateur a pu le
     * supprimer à la main) — jamais une exception, jamais un job en échec.
     */
    private fun removeFile(file: File): Boolean = !file.exists() || runCatching { file.delete() }.getOrDefault(false)

    /** Traces MediaStore : uniquement celles de l'app, oubliées après suppression. */
    private suspend fun deleteTraces(): TraceOutcome {
        val store = store ?: return TraceOutcome(0, 0)
        var deleted = 0
        var left = 0
        val gone = mutableListOf<TracedCapture>()
        store.traced().forEach { entry ->
            if (store.delete(entry)) {
                gone += entry
                deleted++
            } else {
                left++
            }
        }
        store.forgetAll(gone)
        return TraceOutcome(deleted, left)
    }
}
