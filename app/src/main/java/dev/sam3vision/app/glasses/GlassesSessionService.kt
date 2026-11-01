package dev.sam3vision.app.glasses

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.sam3vision.app.MainActivity
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Service de premier plan de la session lunettes-iSee : maintient l'app en vie
 * quand le téléphone passe en veille (écran éteint, app en arrière-plan) pour
 * que le lien Bluetooth, le flux caméra/micro et la voix continuent.
 *
 * Même motif que le guidage vocal (`GuidanceTrackingService`) : notification
 * permanente avec bouton d'arrêt, type `connectedDevice` (déclaré dans le
 * manifeste, minSdk 29). Le service ne possède ni session ni flux — il garde
 * le processus vivant ; le démarrage/arrêt est piloté par le pont lunettes
 * ([GlassesSessionLifecycle], idempotents).
 */
class GlassesSessionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GlassesSessionNotification.ACTION_STOP) {
            // Bouton « Arrêter » : la session tombe (pont lunettes), le service
            // suit. Silencieux si le service n'a jamais démarré (arrêt sans
            // démarrage) : pas de demande de déconnexion impromptue.
            if (GlassesSessionKeepAlive.running.value) {
                GlassesSessionKeepAlive.stopRequests.tryEmit(Unit)
            }
            GlassesSessionKeepAlive.running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        GlassesSessionKeepAlive.running.value = true
        showForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        GlassesSessionKeepAlive.running.value = false
        super.onDestroy()
    }

    /** Notification permanente obligatoire d'un service de premier plan. */
    private fun showForeground() {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, GlassesSessionService::class.java)
                .setAction(GlassesSessionNotification.ACTION_STOP),
            FLAGS,
        )
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        createChannel()
        val notification = NotificationCompat.Builder(this, GlassesSessionNotification.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(GlassesSessionNotification.title())
            .setContentText(GlassesSessionNotification.contentText())
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, GlassesSessionNotification.stopLabel(), stop)
            .build()
        startForeground(
            GlassesSessionNotification.NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    /** Canal unique de la session, créé une fois par installation. */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            GlassesSessionNotification.CHANNEL_ID,
            GlassesSessionNotification.channelName(),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        /** Drapeaux d'un PendingIntent autorisé depuis Android 12. */
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /**
         * Démarrage demandé et pas encore arrêté : posé ici, synchronement, pour
         * que `stop` reste un no-op sans démarrage préalable — sans ce drapeau,
         * chaque retour au repos livrerait un démarrage juste pour l'arrêter.
         * Les intents partent dans l'ordre, donc un démarrage suivi aussitôt
         * d'un arrêt s'arrête bien au lieu de survivre.
         */
        @Volatile
        private var startRequested = false

        /**
         * Démarre le maintien en fond ; no-op répété sans effet (le service
         * ignore les démarrages doublés). Ne lève jamais : un démarrage depuis
         * l'arrière-plan est refusé par Android 12+, il se rejoue à la reprise.
         */
        fun start(context: Context) {
            startRequested = true
            runCatching {
                val app = context.applicationContext
                app.startForegroundService(Intent(app, GlassesSessionService::class.java))
            }
        }

        /** Arrête le maintien en fond ; no-op sans démarrage préalable. */
        fun stop(context: Context) {
            if (!startRequested) return
            startRequested = false
            runCatching {
                val app = context.applicationContext
                val intent = Intent(app, GlassesSessionService::class.java)
                    .setAction(GlassesSessionNotification.ACTION_STOP)
                app.startService(intent)
            }
        }
    }
}

/** État du maintien en fond observable par l'interface et le pont lunettes. */
object GlassesSessionKeepAlive {
    /** Vrai tant que le service tourne (notification affichée). */
    val running: MutableStateFlow<Boolean> = MutableStateFlow(false)

    /** Demandes d'arrêt de session (bouton « Arrêter » de la notification). */
    val stopRequests: MutableSharedFlow<Unit> = MutableSharedFlow(extraBufferCapacity = 1)
}
