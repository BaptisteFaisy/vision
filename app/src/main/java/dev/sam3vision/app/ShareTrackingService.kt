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
import dev.sam3vision.core.share.normalizeShareId
import dev.sam3vision.core.share.relayBaseOf
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
 * Foreground service of permanent tracking: keeps sending the position to
 * the parent site while the screen is off or the app is in the background.
 *
 * Started by "📤 Share tracking" (Settings): permanent "Live tracking
 * active" notification with a "Stop tracking" button. Publishes
 * `{shareId, writeKey, fix}` — no `objects`, the camera is closed outside
 * the Direct — every 2 s with the screen on, 15 s with it off
 * ([ShareTrackingPolicy]). During the Direct the screen already publishes
 * itself ([ShareGate]): the service steps aside to never double-send.
 */
class ShareTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reporter = ShareReporter()

    /** Attached in onCreate: a Service has no Context before attach(). */
    private lateinit var positions: SharePosition
    private lateinit var store: SettingsStore

    /** A single send loop, never relaunched (repeated starts are no-op). */
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
     * Share loop: GPS listen open, send while the notification lives; rate
     * decided by [ShareTrackingPolicy] (screen on/off, Direct already
     * publishing). Cuts itself when the channel is missing.
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

    /** One send; `false` = sharing must stop (missing channel). */
    private suspend fun sendOnce(settings: Settings): Boolean {
        if (!canPublishShare(settings)) return false
        val id = normalizeShareId(settings.shareId)
        if (id.isBlank()) return false
        if (ShareGate.screenPublishing) return true // the Direct already publishes its full state
        val fix = positions.lastFix
        if (fix == null) { // no position yet: wait, and say so
            ShareHealth.noFix()
            return true
        }
        val url = shareEndpoint(relayBaseOf(settings.parentSiteUrl)) ?: return false
        ShareHealth.note(
            reporter.maybeSend(
                System.currentTimeMillis(),
                url,
                shareJson(id, settings.shareWriteKey, fix, emptyList()),
            ),
        )
        return true
    }

    /** Re-reads the saved settings (the channel may change mid-tracking). */
    private suspend fun readSettings(): Settings = store.load()

    /** Mandatory permanent notification of a foreground service. */
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
            .setContentTitle("Live tracking active")
            .setContentText("Your position is shared with your caregiver on the tracking site.")
            .setOngoing(true)
            .setContentIntent(back)
            .addAction(0, "Stop tracking", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Single tracking channel, created once per install. */
    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live tracking",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "suivi_parent"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "dev.sam3vision.app.share.STOP"

        /** Flags of a PendingIntent allowed since Android 12. */
        const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        /** Starts tracking; no-op when already alive (idempotent). */
        fun start(context: Context) {
            val intent = Intent(context, ShareTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}

/** Tracking state observable by the UI (Settings). */
object ShareTracking {
    /** True while the tracking service runs (notification showing). */
    val running: MutableStateFlow<Boolean> = MutableStateFlow(false)
}

/** Anti-duplicate latch: when the Direct screen publishes, the service steps back. */
object ShareGate {
    /** True while the Direct publishes itself (GPS relay of objects + position). */
    @Volatile
    var screenPublishing: Boolean = false
}
