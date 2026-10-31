package dev.sam3vision.app.live

import android.os.SystemClock
import android.util.Log

/**
 * Journal logcat du mouvement V1 (fond contre boîte) : tag unique [TAG]
 * (`adb logcat -s iSeeMotion`). Seules les transitions de verdict y passent
 * ([logTransition]), au plus une ligne par seconde : jamais une ligne par frame,
 * même si le verdict oscille entre « voiture bouge » et « caméra bouge ».
 */
object MotionLog {

    const val TAG = "iSeeMotion"

    /** Intervalle minimal entre deux lignes de transition : 1 s (garde anti-oscillation). */
    const val TRANSITION_MIN_INTERVAL_MS = 1_000L

    private var lastVerdict: String? = null
    private var lastTransitionAt = 0L

    /** Étape rare du mouvement (session, repli). Ne lève jamais. Jamais par frame. */
    fun info(message: String) {
        Log.i(TAG, message)
    }

    /** Détail du mouvement (diagnostic fin). Ne lève jamais. Jamais par frame. */
    fun debug(message: String) {
        Log.d(TAG, message)
    }

    /**
     * Verdict V1 de la frame ([verdict] = étiquette « voiture bouge » ou « caméra
     * bouge », [bgChange] = score de changement du fond, [gapMeanPx]/[gapP95Px] =
     * écart global-boîte) : n'écrit que si le verdict change, au plus une ligne par
     * seconde — une oscillation plus rapide met à jour le verdict suivi sans écrire.
     * Ne lève jamais.
     */
    @Synchronized
    fun logTransition(verdict: String, bgChange: Float, gapMeanPx: Float, gapP95Px: Float) {
        if (verdict == lastVerdict) return
        val previous = lastVerdict ?: "none"
        lastVerdict = verdict
        val now = SystemClock.elapsedRealtime()
        if (now - lastTransitionAt < TRANSITION_MIN_INTERVAL_MS) return
        lastTransitionAt = now
        Log.i(TAG, "motion $previous -> $verdict (bg=$bgChange gap=$gapMeanPx/$gapP95Px px)")
    }

    /** Oublie le verdict suivi (nouvelle séance, sortie d'écran). */
    @Synchronized
    fun reset() {
        lastVerdict = null
        lastTransitionAt = 0L
    }
}
