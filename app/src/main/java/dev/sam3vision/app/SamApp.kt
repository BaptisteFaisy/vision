package dev.sam3vision.app

import android.app.Application
import dev.sam3vision.app.cleanup.CleanupScheduler

/**
 * `Application` de l'app : planifie la purge horaire des captures dès le démarrage.
 * `KEEP` garantit qu'un redémarrage ne crée pas un second job.
 */
class SamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CleanupScheduler.schedule(this)
    }
}
