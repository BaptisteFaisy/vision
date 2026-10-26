package dev.sam3vision.app.cleanup

import android.content.Context

/**
 * Assemble la purge réelle : périmètre privé de l'app + traces Room/MediaStore.
 * Point d'entrée unique pour le [CaptureCleanupWorker] et pour les tests instrumentés,
 * qui peuvent injecter leurs propres racines et un store factice via [CaptureCleaner].
 */
object CleanupRunner {

    /** Purge configurée par [CleanupConfig.Default] (période, types, rétention). */
    fun cleaner(context: Context, config: CleanupConfig = CleanupConfig.Default): CaptureCleaner {
        val dao = CleanupDatabase.get(context).tracedCaptures()
        val store = RoomTracedCaptureStore(dao, context.contentResolver, context.packageName)
        return CaptureCleaner(CapturePaths.privateRoots(context), store, config)
    }

    /** Une passe, jamais d'exception : un échec devient un bilan vide + log. */
    suspend fun run(context: Context): CleanupReport =
        runCatching { cleaner(context).runOnce() }.getOrElse { CleanupReport() }
}
