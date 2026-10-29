package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.ShareReporter
import dev.sam3vision.core.share.SharedPhoto
import dev.sam3vision.core.share.SharedScene
import dev.sam3vision.core.share.SharedTrip
import dev.sam3vision.core.share.canPublishShare
import dev.sam3vision.core.share.normalizeShareId
import dev.sam3vision.core.share.relayBaseOf
import dev.sam3vision.core.share.shareEndpoint
import dev.sam3vision.core.share.shareJson
import dev.sam3vision.core.share.sharedObjectsOf

/**
 * Relais vers le site parent, piloté par les écrans iSee et Direct (et non le ViewModel,
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

    /**
     * Envoie l'état courant si complet, sinon ne fait rien. Sans objet mesuré
     * (couche nulle, calibrage inconnu, scène vide), la position part quand
     * même : sinon la carte restait OFFLINE alors que le téléphone partageait.
     * L'identifiant est normalisé (`normalizeShareId`) : brut, « Léo » répond
     * 400 côté relais. Un champ site effacé retombe sur le site par défaut.
     * [photo] et [trip] sont facultatifs : sans caméra ou hors guidage, le paquet
     * reste celui d'avant (position, objets, phrase).
     */
    suspend fun publish(
        nowMs: Long,
        settings: Settings,
        layer: DistanceLayer?,
        scene: SharedScene? = null,
        trip: SharedTrip? = null,
        photo: SharedPhoto? = null,
    ) {
        if (!canPublishShare(settings)) return
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return
        val fix = positions.lastFix
        if (fix == null) {
            ShareHealth.noFix()
            return
        }
        val objects = layer?.let { sharedObjectsOf(it.readings, it.width, it.height, it.fovDeg, fix.headingDeg) }.orEmpty()
        val url = shareEndpoint(relayBaseOf(settings.parentSiteUrl)) ?: return
        ShareHealth.note(
            reporter.maybeSend(
                nowMs,
                url,
                shareJson(id, settings.shareWriteKey, fix, objects, scene, photo, trip),
            ),
        )
    }

}
