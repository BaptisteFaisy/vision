package dev.sam3vision.app

import android.app.Application
import dev.sam3vision.app.cleanup.CleanupScheduler
import dev.sam3vision.app.isee.PictoUpgradePurge
import dev.sam3vision.app.safety.CrashGuard

/**
 * `Application` de l'app : installe le filet d'exception (toute exception laisse une trace
 * avant de tuer le process), purge les pictos d'une ancienne version, puis planifie la purge
 * horaire des captures. `KEEP` garantit qu'un redémarrage ne crée pas un second job.
 *
 * Rien ici ne peut lever : une exception dans `onCreate` tue l'app avant la première fenêtre,
 * donc à chaque lancement (crash-loop). [CrashGuard.install] ne lit ni n'écrit rien : il pose
 * un chemin de journal et deux références, le démarrage n'est pas allongé.
 */
class SamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(filesDir)
        // État réseau du téléphone : le direct n'attend plus son délai de frame pour
        // apprendre qu'il est hors ligne, et un passage Wi-Fi → 4G rouvre aussitôt.
        runCatching { dev.sam3vision.app.net.NetMonitor.install(this) }
            .onFailure { CrashGuard.note("net-monitor", it) }
        PictoUpgradePurge.maybePurge(this)
        runCatching { CleanupScheduler.schedule(this) }
            .onFailure { CrashGuard.note("cleanup-schedule", it) }
    }
}
