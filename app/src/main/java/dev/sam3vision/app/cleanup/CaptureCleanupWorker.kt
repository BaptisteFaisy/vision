package dev.sam3vision.app.cleanup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Purge horaire silencieuse. Planifié par [CleanupScheduler] depuis `Application.onCreate`.
 *
 * - `KEEP` côté scheduler : un seul job, même après redémarrage de l'appareil ;
 * - aucune contrainte réseau : la purge tourne hors connexion ;
 * - `Result.success()` dans tous les cas : une erreur (fichier verrouillé, provider en
 *   vrac) ne doit pas faire grossir la file de retries — la prochaine heure reprend.
 */
class CaptureCleanupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val report = CleanupRunner.run(applicationContext)
        CleanupLog.append(applicationContext, report)
        return Result.success()
    }
}
