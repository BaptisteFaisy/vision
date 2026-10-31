package dev.sam3vision.app.danger

import android.content.Context
import android.util.Log
import dev.sam3vision.app.depth.DepthPixels
import dev.sam3vision.core.danger.ApproachEstimator
import dev.sam3vision.core.danger.ApproachReading
import dev.sam3vision.core.danger.DangerSnapshot
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
    private val queue = DangerFrameQueue()
    private var running = false
    private var detector: PersonDetector? = null
    private var engineTriedMs = 0L
    private var lastKey: String? = null
    private var lastSnapshot: DangerSnapshot = DangerSnapshot.Empty

    /** File courte des frames à analyser (3 max : la plus vieille est jetée, pas empilée). */
    fun offer(jpegB64: String) {
        if (jpegB64.isBlank()) return
        synchronized(lock) {
            queue.offer(jpegB64)
            if (running) return
            running = true
        }
        scope.launch(background) { pump() }
    }

    /** Dernière frame analysée : pistes + dimensions (pour la voix). */
    fun snapshot(): DangerSnapshot = synchronized(lock) { lastSnapshot }

    /** Oublie files, pistes, historique et moteur (nouvelle séance). */
    fun reset() {
        val stale: PersonDetector?
        synchronized(lock) {
            queue.clear()
            lastKey = null
            lastSnapshot = DangerSnapshot.Empty
            stale = detector
            detector = null
        }
        tracker.reset()
        approach.reset()
        if (stale != null) stale.close()
    }

    private suspend fun pump() {
        try {
            // File courte, dans l'ordre : 3 frames max — une image plus
            // vieille n'apprend rien sur le danger.
            var jpeg = takePending()
            while (jpeg != null) {
                infer(jpeg)
                jpeg = takePending()
            }
        } finally {
            synchronized(lock) { running = false }
            synchronized(lock) {
                if (queue.hasPending() && !running) {
                    running = true
                    scope.launch(background) { pump() }
                }
            }
        }
    }

    private fun takePending(): String? = synchronized(lock) { queue.poll() }

    private suspend fun infer(jpegB64: String) {
        try {
            val engine = ensureEngine() ?: return
            val frame = DepthPixels.frame(jpegB64) ?: return
            val result = engine.detect(frame.argb, frame.width, frame.height).getOrNull() ?: return
            val nowMs = android.os.SystemClock.elapsedRealtime()
            val tracks = tracker.update(result.persons, nowMs)
            synchronized(lock) { lastSnapshot = DangerSnapshot(tracks, frame.width, frame.height) }
            approach.retainAlive(tracks.map { it.id }.toSet())
            publish(pickDangerAlert(tracks.map { approach.update(it, nowMs) }, result.persons.size))
        } catch (e: Exception) {
            Log.w(TAG, "inférence: " + e.message)
        }
    }

    /** Publie l'alerte si elle a changé (apparition, piste, seconde ou sens) ; logue. */
    private fun publish(alert: DangerAlert?) {
        val key = alert?.let { "${it.trackId}:${it.ttcSec.toInt()}:${it.crossing}" }
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
        /**
         * File courte : 3 frames max en attente — quand YOLO rame, le tracker
         * garde la continuité au lieu de sauter à la dernière seule.
         */
        const val MAX_PENDING_FRAMES = 3
        private const val RETRY_MS = 60_000L
    }
}

/**
 * File courte des frames danger (FIFO bornée) : [offer] enfile en jetant la
 * plus vieille au-delà du plafond, [poll] défile dans l'ordre. Pure, testée
 * en JVM (le pipeline, lui, exige Android).
 */
internal class DangerFrameQueue(private val maxFrames: Int = DangerPipeline.MAX_PENDING_FRAMES) {
    private val frames = ArrayDeque<String>()

    /** Enfile, en jetant les plus vieilles au-delà du plafond. */
    fun offer(jpegB64: String) {
        frames.addLast(jpegB64)
        while (frames.size > maxFrames) frames.removeFirst()
    }

    /** Défile la plus vieille, `null` si vide. */
    fun poll(): String? = if (frames.isEmpty()) null else frames.removeFirst()

    /** Reste-t-il une frame à analyser ? */
    fun hasPending(): Boolean = frames.isNotEmpty()

    /** Vide la file (nouvelle séance). */
    fun clear() {
        frames.clear()
    }

    /** Frames en attente. */
    val size: Int get() = frames.size
}

/**
 * La plus urgente des pistes qui s'approchent (ttc minimal sous le seuil),
 * de face comme en traversée ([ApproachReading.crossing]). Pure, testée en JVM.
 */
internal fun pickDangerAlert(readings: List<ApproachReading>, persons: Int): DangerAlert? {
    var best: ApproachReading? = null
    for (reading in readings) {
        val ttc = reading.ttcSec ?: continue
        if (!reading.approaching || ttc > DangerAlert.ALERT_TTC_SEC) continue
        val current = best?.ttcSec
        if (current == null || ttc < current) best = reading
    }
    val winner = best ?: return null
    return DangerAlert(winner.trackId, winner.ttcSec ?: return null, persons, winner.crossing)
}
