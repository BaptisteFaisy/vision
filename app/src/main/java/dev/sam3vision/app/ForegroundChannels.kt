package dev.sam3vision.app

import android.app.NotificationChannel
import android.app.NotificationManager

/**
 * Canaux des services de premier plan : un canal principal par service, plus un
 * **canal de secours** (`<id>_fallback`), tous deux en importance basse.
 *
 * Le secours existe pour qu'un canal principal absent, refusé ou recréé par
 * l'utilisateur ne transforme pas un démarrage en `startForeground` impossible : le
 * service bascule dessus au lieu de laisser expirer l'obligation de cinq secondes
 * (`ForegroundServiceDidNotStartInTimeException`).
 */
object ForegroundChannels {

    /** Id du canal de secours d'un canal principal [primaryId]. */
    fun fallbackId(primaryId: String): String = "${primaryId}_fallback"

    /**
     * Crée [channelId] (idempotent : recréer un canal existant ne change que son nom)
     * et dit s'il existe ensuite. Ne lève jamais : un canal impossible se traduit en
     * `false`, jamais en exception sur le chemin d'un démarrage de service.
     */
    fun ensure(manager: NotificationManager?, channelId: String, name: String): Boolean {
        if (manager == null) return false
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(channelId, name, NotificationManager.IMPORTANCE_LOW),
            )
        }
        return runCatching { manager.getNotificationChannel(channelId) != null }.getOrDefault(false)
    }
}
