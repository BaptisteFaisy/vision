package dev.sam3vision.app.glasses

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
import dev.sam3vision.app.voicenav.ForegroundFx
import dev.sam3vision.app.voicenav.GuidanceServiceRunner
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
class GlassesSessionService : Service(), ForegroundFx {

    private lateinit var runner: GuidanceServiceRunner

    /** Vrai entre `startForeground` tenu et la sortie : garde d'idempotence du runner. */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runner = GuidanceServiceRunner(this)
        // Premier plan tenu au plus tôt : l'obligation de cinq secondes ne dépend pas
        // de l'arrivée de `onStartCommand`.
        holdForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == GlassesSessionNotification.ACTION_STOP) {
            // Bouton « Arrêter » : la session tombe (pont lunettes), le service
            // suit. Silencieux si le service n'a jamais démarré (arrêt sans
            // démarrage) : pas de demande de déconnexion impromptue.
            if (GlassesSessionKeepAlive.running.value) {
                GlassesSessionKeepAlive.stopRequests.tryEmit(Unit)
            }
            GlassesSessionKeepAlive.running.value = false
            runner.stop() // gardé : le chemin d'arrêt ne peut pas lever
            return START_NOT_STICKY
        }
        GlassesSessionKeepAlive.running.value = true
        return if (holdForeground()) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        GlassesSessionKeepAlive.running.value = false
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
            GlassesSessionKeepAlive.running.value = false
            stopSelf()
        }
        return held
    }

    // --- ForegroundFx : les effets Android, tous gardés par le runner ---

    /** Canal principal d'abord, secours ensuite ; `false` = principal indisponible. */
    override fun ensureChannel(): Boolean {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ForegroundChannels.ensure(manager, FALLBACK_CHANNEL_ID, FALLBACK_CHANNEL_NAME)
        return ForegroundChannels.ensure(
            manager,
            GlassesSessionNotification.CHANNEL_ID,
            GlassesSessionNotification.channelName(),
        )
    }

    override fun enterForeground(fallback: Boolean) {
        val channelId = if (fallback) FALLBACK_CHANNEL_ID else GlassesSessionNotification.CHANNEL_ID
        startForeground(
            GlassesSessionNotification.NOTIFICATION_ID,
            notification(channelId),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
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
            Intent(this, GlassesSessionService::class.java)
                .setAction(GlassesSessionNotification.ACTION_STOP),
            FLAGS,
        )
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(GlassesSessionNotification.title())
            .setContentText(GlassesSessionNotification.contentText())
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, GlassesSessionNotification.stopLabel(), stop)
            .build()
    }

    companion object {
        private const val TAG = "GlassesSession"

        /** Canal de secours : un canal principal absent ne doit jamais faire échouer le premier plan. */
        const val FALLBACK_CHANNEL_ID = "lunettes_isee_fallback"

        private const val FALLBACK_CHANNEL_NAME = "Session lunettes (secours)"

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
