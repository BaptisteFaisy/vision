package dev.sam3vision.app.voicenav

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.sam3vision.app.ForegroundChannels
import dev.sam3vision.app.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Service de premier plan du guidage vocal : maintient l'app en vie quand
 * elle passe en fond (home, écran éteint) pour que le GPS et les annonces
 * vocales continuent jusqu'à l'arrivée.
 *
 * Même motif que le suivi parent ([dev.sam3vision.app.ShareTrackingService]) :
 * notification permanente avec bouton « Arrêter le guidage », type
 * `location` (déclaré dans le manifeste). Le service ne possède ni GPS ni
 * voix — il garde le processus vivant ; le démarrage/arrêt est piloté par
 * le cycle de vie du guidage ([start], [stop], idempotents).
 *
 * **Premier plan tenu dès [onCreate]** : l'obligation des cinq secondes est tenue avant
 * tout traitement de l'intent, et [GuidanceServiceRunner] garantit qu'aucune branche
 * (y compris `ACTION_STOP`) ne sort sans premier plan ni exception.
 */
class GuidanceTrackingService : Service(), ForegroundFx {

    /** Dernière destination connue, pour le texte de la notification. */
    private var destinationLabel: String? = null

    private lateinit var runner: GuidanceServiceRunner

    /** Vrai entre `startForeground` tenu et la sortie : garde d'idempotence du runner. */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runner = GuidanceServiceRunner(this)
        // Première ligne utile : même si `onStartCommand` n'est jamais atteint (thread
        // principal saturé par un `window_resize`), le premier plan est déjà tenu.
        holdForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GuidanceNotification.ACTION_STOP) {
            GuidanceTracking.running.value = false
            runner.stop() // gardé : l'arrêt ne peut pas lever
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_DESTINATION)?.let { destinationLabel = it }
        GuidanceTracking.running.value = true
        return if (holdForeground()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        GuidanceTracking.running.value = false
        foreground = false
        super.onDestroy()
    }

    /**
     * Tient le premier plan via le runner et journalise les incidents ; `false` = refus,
     * le service s'est arrêté proprement au lieu de laisser le système tuer le processus.
     */
    private fun holdForeground(): Boolean {
        val held = runner.start()
        val label = if (held) "premier plan" else "premier plan refusé, sortie propre"
        runner.incidents.forEach { Log.w(TAG, "$label : $it") }
        if (!held) {
            GuidanceTracking.running.value = false
            stopSelf()
        }
        return held
    }

    // --- ForegroundFx : les effets Android, tous gardés par le runner ---

    /** Canal principal d'abord, secours ensuite ; `false` = principal indisponible. */
    override fun ensureChannel(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ForegroundChannels.ensure(
            manager,
            GuidanceNotification.FALLBACK_CHANNEL_ID,
            GuidanceNotification.fallbackChannelName(),
        )
        return ForegroundChannels.ensure(
            manager,
            GuidanceNotification.CHANNEL_ID,
            GuidanceNotification.channelName(),
        )
    }

    override fun enterForeground(fallback: Boolean) {
        val channelId = if (fallback) {
            GuidanceNotification.FALLBACK_CHANNEL_ID
        } else {
            GuidanceNotification.CHANNEL_ID
        }
        startForeground(
            GuidanceNotification.NOTIFICATION_ID,
            notification(channelId),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        foreground = true
    }

    override fun exitForeground() {
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun isForeground(): Boolean = foreground

    /** Notification permanente obligatoire d'un service de premier plan. */
    private fun notification(channelId: String): Notification {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, GuidanceTrackingService::class.java)
                .setAction(GuidanceNotification.ACTION_STOP),
            FLAGS,
        )
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(GuidanceNotification.title())
            .setContentText(GuidanceNotification.contentText(destinationLabel))
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, GuidanceNotification.stopLabel(), stop)
            .build()
    }

    companion object {
        private const val TAG = "GuidanceTracking"

        /** Destination affichée dans la notification (optionnelle). */
        const val EXTRA_DESTINATION = "dev.sam3vision.app.voicenav.DESTINATION"

        /** Drapeaux d'un PendingIntent autorisé depuis Android 12. */
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /**
         * Démarre le maintien en fond ; no-op si déjà vivant (idempotent,
         * la notification est simplement rafraîchie avec la destination).
         * Ne lève jamais : un démarrage refusé en arrière-plan (Android 12+) est
         * rejoué à la reprise visible ([GuidanceLifecycle]).
         */
        fun start(context: Context, destinationLabel: String? = null) {
            val intent = Intent(context, GuidanceTrackingService::class.java)
                .putExtra(EXTRA_DESTINATION, destinationLabel)
            runCatching { context.startForegroundService(intent) }
        }

        /**
         * Arrête le maintien en fond ; no-op s'il ne tourne pas. Par `stopService` (et
         * non par `startService(ACTION_STOP)` : démarrer un service depuis l'arrière-plan
         * est interdit sur Android 8+ et ferait planter l'appelant).
         */
        fun stop(context: Context) {
            if (!GuidanceTracking.running.value) return
            runCatching {
                context.stopService(Intent(context, GuidanceTrackingService::class.java))
            }
        }
    }
}

/** État du maintien en fond observable par l'interface. */
object GuidanceTracking {
    /** Vrai tant que le service de guidage tourne (notification affichée). */
    val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
