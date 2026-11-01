package dev.sam3vision.app

import android.app.Application
import dev.sam3vision.app.cleanup.CleanupScheduler
import dev.sam3vision.app.isee.PictoUpgradePurge

/**
 * `Application` de l'app : purge les pictos d'une ancienne version avant tout
 * chargement, puis planifie la purge horaire des captures dès le démarrage.
 * `KEEP` garantit qu'un redémarrage ne crée pas un second job.
 */
class SamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        PictoUpgradePurge.maybePurge(this)
        CleanupScheduler.schedule(this)
    }
}
