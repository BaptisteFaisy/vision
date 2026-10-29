package dev.sam3vision.app.segment

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.sam3vision.app.depth.DepthPixels
import dev.sam3vision.app.depth.ModelDownloader
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.segment.SegmentMask
import dev.sam3vision.core.segment.Segmenter
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Segmentation TwinLiteNet du direct iSee : la dernière frame JPEG part en fond (~3/s),
 * le masque praticable frais (< 1 s) nourrit la flèche et la voix. Miroir de
 * [dev.sam3vision.app.depth.LiveDepthMeter] : même file d'une frame, même pompe unique,
 * même réessai modèle à 60 s — mais TwinLite est primaire (pas un raffinement) : le
 * compteur d'essai n'avance que sur échec et repart à zéro au [reset] (réouverture
 * immédiate), et le moteur reste chaud entre les sessions ([close] seul le ferme).
 */
class LiveSegmentMeter(
    private val scope: CoroutineScope,
    private val appContext: Context,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : SegmentMeter {
    private val lock = Any()
    private var pendingJpeg: String? = null
    private var running = false
    private var latest: SegmentMask? = null
    private var latestAtMs = 0L
    private var segmenter: Segmenter? = null
    private var engineTriedMs = 0L
    private var lastRunMs = 0L
    private var runs = 0
    private var closed = false
    private val fpsMeter = LiveFpsMeter()

    /** Cadence effective des segmentations (ips sur intervalles réels). */
    @Volatile
    var fps: Float = 0f
        private set

    /** Dernière frame à segmenter (les intermédiaires sont jetées, pas empilées). */
    override fun offer(jpegB64: String) {
        if (jpegB64.isBlank()) return
        synchronized(lock) {
            if (closed) return
            pendingJpeg = jpegB64
            if (running) return
            running = true
        }
        scope.launch(background) { pump() }
    }

    /** Masque praticable s'il a moins d'une seconde, `null` sinon (overlay caché). */
    override fun latestFresh(): SegmentMask? {
        synchronized(lock) {
            if (latestAtMs <= 0L || nowMs() - latestAtMs > FRESH_MS) return null
            return latest
        }
    }

    /** Nouvelle session : file, masque et cadence oubliés, moteur gardé chaud. */
    override fun reset() {
        synchronized(lock) {
            pendingJpeg = null
            latest = null
            latestAtMs = 0L
            lastRunMs = 0L
            engineTriedMs = 0L
            fpsMeter.reset()
            fps = 0f
        }
    }

    /** Fin de vie : [reset] puis moteur LiteRT fermé (terminal, [offer] neutre). */
    override fun close() {
        val stale: Segmenter?
        synchronized(lock) {
            closed = true
            stale = segmenter
            segmenter = null
        }
        reset()
        stale?.close()
    }

    private suspend fun pump() {
        try {
            for (i in 0 until DRAIN_ROUNDS) {
                val jpeg = takePending() ?: break
                infer(jpeg)
            }
        } finally {
            synchronized(lock) { running = false }
        }
    }

    private fun takePending(): String? = synchronized(lock) {
        val jpeg = pendingJpeg
        pendingJpeg = null
        jpeg
    }

    private suspend fun infer(jpegB64: String) {
        try {
            if (nowMs() - lastRunMs < THROTTLE_MS) return
            val engine = ensureEngine() ?: return
            lastRunMs = nowMs()
            val frame = DepthPixels.frame(jpegB64) ?: return
            val result = engine.segment(frame.argb, frame.width, frame.height).getOrNull() ?: return
            val gap = fpsMeter.gapAt(nowMs())
            fps = LiveFpsMeter.fpsFor(gap, fps)
            runs += 1
            synchronized(lock) {
                latest = result.driveable
                latestAtMs = nowMs()
            }
            logRun(result.driveable, result.inferenceMs)
        } catch (e: Exception) {
            Log.w(TAG, "twinlite: inférence : " + e.message)
        }
    }

    private suspend fun ensureEngine(): Segmenter? {
        synchronized(lock) { segmenter }?.let { return it }
        if (nowMs() - engineTriedMs < RETRY_MS) return null
        val ensured = ModelDownloader.ensureSpec(
            appContext.filesDir,
            SegmentModels.TWINLITE,
            dir = SegmentModelStore.DIR,
        )
        if (ensured.isFailure) {
            engineTriedMs = nowMs()
            Log.w(TAG, "twinlite: modèle : " + ensured.exceptionOrNull()?.message)
            return null
        }
        val opened = LiteRtTwinLiteSegmenter.open(appContext)
        if (opened == null) {
            engineTriedMs = nowMs()
            Log.w(TAG, "twinlite: moteur indisponible")
            return null
        }
        Log.i(TAG, "twinlite: moteur TWINLITE prêt")
        synchronized(lock) {
            segmenter = opened
        }
        return opened
    }

    /** Une ligne de mesure toutes les 20 segmentations (couverture, ms, ips). */
    private fun logRun(drive: SegmentMask, ms: Long) {
        if (runs % LOG_EVERY_RUNS != 0) return
        val cover = String.format(Locale.US, "drive=%.0f%%", drive.coverage() * 100)
        val delegate = synchronized(lock) { segmenter?.delegateName } ?: "?"
        Log.i(TAG, "twinlite: $cover, $ms ms, " + String.format(Locale.US, "%.1f", fps) + " ips [$delegate]")
    }

    private fun nowMs(): Long = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "sam3vision"
        private const val FRESH_MS = 1000L
        private const val RETRY_MS = 60_000L
        private const val THROTTLE_MS = 300L
        private const val DRAIN_ROUNDS = 4
        private const val LOG_EVERY_RUNS = 20
    }
}
