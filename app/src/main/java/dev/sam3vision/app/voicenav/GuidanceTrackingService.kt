package dev.sam3vision.app.voicenav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
 */
class GuidanceTrackingService : Service() {

    /** Dernière destination connue, pour le texte de la notification. */
    private var destinationLabel: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GuidanceNotification.ACTION_STOP) {
            GuidanceTracking.running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        intent?.getStringExtra(EXTRA_DESTINATION)?.let { destinationLabel = it }
        GuidanceTracking.running.value = true
        showForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        GuidanceTracking.running.value = false
        super.onDestroy()
    }

    /** Notification permanente obligatoire d'un service de premier plan. */
    private fun showForeground() {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, GuidanceTrackingService::class.java)
                .setAction(GuidanceNotification.ACTION_STOP),
            FLAGS,
        )
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        createChannel()
        val notification = NotificationCompat.Builder(this, GuidanceNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(GuidanceNotification.title())
            .setContentText(GuidanceNotification.contentText(destinationLabel))
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, GuidanceNotification.stopLabel(), stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                GuidanceNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(GuidanceNotification.NOTIFICATION_ID, notification)
        }
    }

    /** Canal unique du guidage, créé une fois par installation. */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            GuidanceNotification.CHANNEL_ID,
            GuidanceNotification.channelName(),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Destination affichée dans la notification (optionnelle). */
        const val EXTRA_DESTINATION = "dev.sam3vision.app.voicenav.DESTINATION"

        /** Drapeaux d'un PendingIntent autorisé depuis Android 12. */
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /**
         * Démarre le maintien en fond ; no-op si déjà vivant (idempotent,
         * la notification est simplement rafraîchie avec la destination).
         */
        fun start(context: Context, destinationLabel: String? = null) {
            val intent = Intent(context, GuidanceTrackingService::class.java)
                .putExtra(EXTRA_DESTINATION, destinationLabel)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Arrête le maintien en fond ; no-op s'il ne tourne pas. */
        fun stop(context: Context) {
            if (!GuidanceTracking.running.value) return
            val intent = Intent(context, GuidanceTrackingService::class.java)
                .setAction(GuidanceNotification.ACTION_STOP)
            context.startService(intent)
        }
    }
}

/** État du maintien en fond observable par l'interface. */
object GuidanceTracking {
    /** Vrai tant que le service de guidage tourne (notification affichée). */
    val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
}
