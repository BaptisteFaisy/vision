package dev.sam3vision.app.live

import android.os.SystemClock
import android.util.Log

/**
 * Journal logcat du transport du direct : tag unique [TAG]
 * (`adb logcat -s iSeeLive`). Les échecs de frames sont limités à un par
 * seconde ([failure]) pour ne pas noyer le logcat quand le GPU est injoignable.
 */
object LiveTransportLog {

    const val TAG = "iSeeLive"

    /** Intervalle minimal entre deux logs d'échec : 1 s. */
    const val FAILURE_MIN_INTERVAL_MS = 1_000L

    private var lastFailureAt = 0L

    /** Étape du transport (choix, repli, session). Ne lève jamais. */
    fun info(message: String) {
        Log.i(TAG, message)
    }

    /** Détail du transport (diagnostic fin). Ne lève jamais. */
    fun debug(message: String) {
        Log.d(TAG, message)
    }

    /**
     * Échec de frame, au plus un log par seconde : les appels surnuméraires
     * sont jetés silencieusement. Ne lève jamais.
     */
    @Synchronized
    fun failure(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFailureAt < FAILURE_MIN_INTERVAL_MS) return
        lastFailureAt = now
        Log.w(TAG, message)
    }
}
