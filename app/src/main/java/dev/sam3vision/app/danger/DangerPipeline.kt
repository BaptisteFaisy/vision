package dev.sam3vision.app.danger

import android.content.Context
import android.util.Log
import dev.sam3vision.app.depth.DepthPixels
import dev.sam3vision.core.danger.ApproachEstimator
import dev.sam3vision.core.danger.PersonTracker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pipeline urgence du direct (miroir de `LiveDepthMeter`) : la sonde lui offre
 * chaque frame capturée (JPEG base64), il infère en tâche de fond — YOLO11n
 * (personnes) → tracker (même personne entre frames) → approche (grossissement
 * → temps-avant-contact) — et publie l'alerte par [onAlert] (changements
 * seuls, pour ne pas réécrire le statut à chaque frame).
 *
 * Le direct ne s'arrête jamais pour le danger : modèle absent, frame illisible
 * ou inférence en échec = pas d'alerte, un mot dans logcat, et ça continue.
 * La voix lira l'alerte plus tard ; pour l'instant : logcat (`DangerAlert`) +
 * ligne de statut (l'appelant affiche `DangerAlert.line`).
 */
class DangerPipeline(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val onAlert: (DangerAlert?) -> Unit,
) {
    private val tracker = PersonTracker()
    private val approach = ApproachEstimator()
    private val lock = Any()
    private var pendingJpeg: String? = null
    private var running = false
    private var detector: PersonDetector? = null
    private var engineTriedMs = 0L
    private var lastKey: String? = null

    /** Dernière frame à analyser (les intermédiaires sont jetées, pas empilées). */
    fun offer(jpegB64: String) {
        if (jpegB64.isBlank()) return
        synchronized(lock) {
            pendingJpeg = jpegB64
            if (running) return
            running = true
        }
        scope.launch(background) { pump() }
    }

    /** Oublie files, pistes, historique et moteur (nouvelle séance). */
    fun reset() {
        val stale: PersonDetector?
        synchronized(lock) {
            pendingJpeg = null
            lastKey = null
            stale = detector
            detector = null
        }
        tracker.reset()
        approach.reset()
        if (stale != null) stale.close()
    }

    private suspend fun pump() {
        try {
            // Dernière frame seule : une vieille image n'apprend rien sur le danger.
            takePending()?.let { infer(it) }
        } finally {
            synchronized(lock) { running = false }
            synchronized(lock) {
                if (pendingJpeg != null && !running) {
                    running = true
                    scope.launch(background) { pump() }
                }
            }
        }
    }

    private fun takePending(): String? = synchronized(lock) {
        val jpeg = pendingJpeg
        pendingJpeg = null
        jpeg
    }

    private suspend fun infer(jpegB64: String) {
        try {
            val engine = ensureEngine() ?: return
            val frame = DepthPixels.frame(jpegB64) ?: return
            val result = engine.detect(frame.argb, frame.width, frame.height).getOrNull() ?: return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val tracks = tracker.update(result.persons, nowMs)
            approach.retainAlive(tracks.map { it.id }.toSet())
            publish(pickAlert(tracks.map { approach.update(it, nowMs) }, result.persons.size))
        } catch (e: Exception) {
            Log.w(TAG, "inférence: " + e.message)
        }
    }

    /** La plus urgente des pistes qui s'approchent (ttc minimal sous le seuil). */
    private fun pickAlert(
        readings: List<dev.sam3vision.core.danger.ApproachReading>,
        persons: Int,
    ): DangerAlert? {
        val best = readings
            .filter { it.approaching && it.ttcSec != null && it.ttcSec <= DangerAlert.ALERT_TTC_SEC }
            .minByOrNull { it.ttcSec!! } ?: return null
        return DangerAlert(best.trackId, best.ttcSec!!, persons)
    }

    /** Publie l'alerte si elle a changé (apparition, piste ou seconde) ; logue. */
    private fun publish(alert: DangerAlert?) {
        val key = alert?.let { "${it.trackId}:${it.ttcSec.toInt()}" }
        synchronized(lock) {
            if (key == lastKey) return
            lastKey = key
        }
        if (alert != null) Log.i(TAG, "ALERTE ttc=${"%.1f".format(java.util.Locale.US, alert.ttcSec)}s " + alert.line())
        onAlert(alert)
    }

    private fun ensureEngine(): PersonDetector? {
        synchronized(lock) { detector }?.let { return it }
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - engineTriedMs < RETRY_MS) return null
        engineTriedMs = nowMs
        val opened = runCatching { PersonDetector.open(appContext) }.getOrNull()
        if (opened == null) {
            Log.w(TAG, "moteur YOLO11n indisponible (asset manquant ?)")
            return null
        }
        Log.i(TAG, "moteur YOLO11n prêt")
        synchronized(lock) { detector = opened }
        return opened
    }

    companion object {
        const val TAG = "DangerAlert"
        private const val RETRY_MS = 60_000L
    }
}
