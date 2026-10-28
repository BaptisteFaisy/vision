package dev.sam3vision.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.SettingsStore
import dev.sam3vision.core.settings.SettingsStoreFactory
import dev.sam3vision.core.share.ShareReporter
import dev.sam3vision.core.share.ShareTrackingPolicy
import dev.sam3vision.core.share.shareJson
import dev.sam3vision.core.share.shareEndpoint
import dev.sam3vision.core.share.canPublishShare
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Service de premier plan du suivi permanent : continue d'envoyer la position au
 * site parent quand l'écran est éteint ou que l'app est en arrière-plan.
 *
 * Lancé par « 📤 Partager le suivi » (Réglages) : notification permanente
 * « Suivi en direct actif » avec bouton « Arrêter le suivi ». Publie
 * `{shareId, writeKey, fix}` — sans `objects`, la caméra est fermée hors
 * Direct — toutes les 2 s écran allumé, 15 s écran éteint ([ShareTrackingPolicy]).
 * Pendant le Direct l'écran publie déjà lui-même ([ShareGate]) : le service
 * s'efface pour ne pas doubler l'envoi.
 */
class ShareTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporter = ShareReporter()

    /** Attachés dans onCreate : un Service n'a pas de Context avant attach(). */
    private lateinit var positions: SharePosition
    private lateinit var store: SettingsStore

    /** Une seule boucle d'envoi, relancée jamais (les start répétés sont no-op). */
    private var loop: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        positions = SharePosition(applicationContext)
        store = SettingsStoreFactory.create(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ShareTracking.running.value = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ShareTracking.running.value = true
        showForeground()
        if (loop == null) loop = scope.launch { track() }
        return START_STICKY
    }

    override fun onDestroy() {
        ShareTracking.running.value = false
        loop = null
        positions.stop()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Boucle de partage : écoute GPS ouverte, envoi tant que la notification est
     * vivante ; cadence décidée par [ShareTrackingPolicy] (écran allumé/éteint,
     * Direct déjà en train de publier). Se coupe si canal manquant.
     */
    private suspend fun track() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        positions.start()
        var settings = readSettings()
        while (coroutineContext.isActive) {
            if (!sendOnce(settings)) {
                stopSelf()
                return
            }
            delay(
                ShareTrackingPolicy.nextDelayMs(
                    interactive = power?.isInteractive ?: true,
                    directPublishing = ShareGate.screenPublishing,
                ),
            )
            settings = readSettings()
        }
    }

    /** Un envoi ; `false` = le partage doit s'arrêter (canal manquant). */
    private suspend fun sendOnce(settings: Settings): Boolean {
        if (!canPublishShare(settings)) return false
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return false
        if (ShareGate.screenPublishing) return true // le Direct publie déjà son état complet
        val fix = positions.lastFix ?: return true // pas encore de position : on patiente
        val site = settings.parentSiteUrl.ifBlank { Settings.DEFAULT_PARENT_SITE_URL }
        val url = shareEndpoint(site) ?: return false
        reporter.maybeSend(
            System.currentTimeMillis(),
            url,
            shareJson(id, settings.shareWriteKey, fix, emptyList()),
        )
        return true
    }

    /** Relit les réglages enregistrés (le canal peut changer pendant le suivi). */
    private suspend fun readSettings(): Settings = store.load()

    /** Notification permanente obligatoire d'un service de premier plan. */
    private fun showForeground() {
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ShareTrackingService::class.java).setAction(ACTION_STOP),
            FLAGS,
        )
        val back = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), FLAGS)
        createChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Suivi en direct actif")
            .setContentText("La position est partagée avec le proche sur le site de suivi.")
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, "Arrêter le suivi", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Canal unique du suivi, créé une fois par installation. */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Suivi en direct",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "suivi_parent"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "dev.sam3vision.app.share.STOP"

        /** Drapeaux d'un PendingIntent autorisé depuis Android 12. */
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** Démarre le suivi ; no-op si déjà vivant (idempotent). */
        fun start(context: Context) {
            val intent = Intent(context, ShareTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

/** État du suivi observable par l'interface (Réglages). */
object ShareTracking {
    /** Vrai tant que le service de suivi tourne (notification affichée). */
    val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/** Verrou anti-doublon : quand l'écran Direct publie, le service se retire. */
object ShareGate {
    /** Vrai tant que le Direct publie lui-même (relais GPS des objets + position). */
    @Volatile
    var screenPublishing: Boolean = false
}
