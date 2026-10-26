package dev.sam3vision.app.cleanup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Planification de la purge : **une** requête périodique unique, [ExistingPeriodicWorkPolicy.KEEP]
 * (jamais de doublon au redémarrage), sans contrainte réseau ni batterie.
 * La périodicité vient de [CleanupConfig.periodHours] — un seul endroit à changer.
 */
object CleanupScheduler {

    fun schedule(context: Context, config: CleanupConfig = CleanupConfig.Default) {
        val request = PeriodicWorkRequestBuilder<CaptureCleanupWorker>(
            CleanupConfig.intervalMinutes(config.periodHours),
            TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .addTag(CleanupConfig.WORK_NAME)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            CleanupConfig.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
