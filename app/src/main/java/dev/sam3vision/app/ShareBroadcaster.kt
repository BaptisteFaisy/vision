package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.ShareReporter
import dev.sam3vision.core.share.shareJson
import dev.sam3vision.core.share.sharedObjectsOf

/**
 * Relais vers le site parent, piloté par la route Direct (et non le ViewModel,
 * pour garder [LiveViewModel] sous la limite de taille) : position GPS + cap
 * ([SharePosition]) et objets en cap absolu, au plus toutes les 2 s.
 *
 * Silencieux sans position (permission refusée), relais injoignable ou partage
 * coupé : le site garde alors le dernier état et l'affiche périmé.
 */
class ShareBroadcaster(context: Context) {

    private val positions = SharePosition(context)
    private val reporter = ShareReporter()

    /** Démarre ou coupe l'écoute GPS selon la bascule des Réglages. */
    fun start(enabled: Boolean) {
        reporter.reset()
        if (enabled) positions.start() else positions.stop()
    }

    /** Coupe l'écoute et oublie la position (sortie du Direct). */
    fun stop() {
        positions.stop()
        reporter.reset()
    }

    /** Envoie l'état courant si complet, sinon ne fait rien. */
    suspend fun publish(nowMs: Long, settings: Settings, layer: DistanceLayer) {
        if (settings.shareId.isBlank()) return
        val fix = positions.lastFix ?: return
        val objects = sharedObjectsOf(layer.readings, layer.width, layer.height, layer.fovDeg, fix.headingDeg)
        val url = settings.parentSiteUrl.trim().trimEnd('/') + SHARE_PATH
        reporter.maybeSend(nowMs, url, shareJson(settings.shareId, settings.shareWriteKey, fix, objects))
    }

    private companion object {
        /** Chemin du relais sur le site parent (même origine, pas de CORS). */
        const val SHARE_PATH = "/api/share"
    }
}
