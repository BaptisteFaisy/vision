package dev.sam3vision.glasses

import android.os.SystemClock
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.types.StreamError
import kotlinx.coroutines.delay

/**
 * Phase de frames : collecte brute du flux DAT puis étranglement vers [onFrame].
 *
 * Le débit et la politique d'erreur viennent de `GlassesSessionPolicy`
 * ([throttleDelayMs], [streamErrorDisposition]) : ici on ne fait qu'attendre et
 * transmettre.
 */

/**
 * Collecte brute : l'envoi ne suspend jamais, donc une consommation lente ne
 * fait pas exploser la latence du flux.
 */
internal suspend fun GlassesRuntime.collectFrames(stream: Stream) {
    stream.videoStream.collect { frame ->
        // Les frames de configuration codec ne portent pas d'image : les laisser
        // entrer évincerait une vraie frame du canal conflaté.
        if (frame.isCodecConfig) return@collect
        frames.trySend(frame)
    }
}

/** Une erreur de flux transitoire (batterie, charnière, thermique) n'arrête rien. */
internal fun GlassesRuntime.onStreamError(error: StreamError) {
    onLog("erreur de flux : ${describe(error)}")
    if (streamErrorDisposition(error) != StreamErrorDisposition.STOP_CYCLE) return
    onLog("erreur critique du flux : arrêt du cycle")
    stopCycle()
}

/**
 * Étranglement : au plus une frame toutes les [FRAME_THROTTLE_MS] vers [onFrame].
 * L'appel réseau SAM + LLM est cher, ~0,7 image/s suffit.
 */
internal suspend fun GlassesRuntime.consumeFrames() {
    var lastSentAt = 0L
    for (frame in frames) {
        val wait = throttleDelayMs(lastSentAt, SystemClock.elapsedRealtime())
        if (wait > 0) delay(wait)
        // Pendant l'attente une frame plus récente a pu arriver : elle vaut mieux
        // que celle qu'on tenait depuis 1,5 s.
        val fresh = frames.tryReceive().getOrNull() ?: frame
        lastSentAt = SystemClock.elapsedRealtime()
        val jpeg = io { encoder.toJpeg(fresh) }
        if (jpeg == null) {
            onLog("frame non encodable : ignorée")
            continue
        }
        onLog("frame transmise : ${fresh.width}x${fresh.height}")
        onFrame(jpeg)
    }
}