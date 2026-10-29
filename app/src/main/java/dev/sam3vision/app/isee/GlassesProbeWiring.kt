package dev.sam3vision.app.isee

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.uprightScaled
import kotlinx.coroutines.delay

/**
 * Alimente la sonde SAM depuis la rue des lunettes : quand le direct vit et que la sonde
 * veut une frame, on photographie la rue affichée (même image que l'utilisateur voit),
 * on l'encode comme le téléphone (JPEG 320 q55 redressé) et on l'envoie. Guidage,
 * distances et voix suivent alors le même chemin que le mode téléphone : parité par
 * construction, sans toucher à [Guidance][core.live.Guidance].
 */
@Composable
internal fun GlassesProbeWiring(probe: LiveProbe, glassesLive: Boolean) {
    LaunchedEffect(probe, glassesLive) {
        if (!glassesLive) return@LaunchedEffect
        while (true) {
            delay(BRIDGE_POLL_MS)
            if (!probe.wantsFrame()) continue
            val jpeg = snapshotJpeg() ?: continue
            probe.onFrame(jpeg, SystemClock.elapsedRealtimeNanos())
            // Même seau que le téléphone : la couleur du feu se relit ici aussi (≤ 150 ms).
            if (probe.state.value.crossingActive) CrossingLightPump.offerFrame(jpeg)
        }
    }
}

/** Photo de la rue → JPEG d'envoi ; les bitmaps jetables sont recyclés aussitôt. */
private fun snapshotJpeg(): String? {
    val bitmap = GlassesVideoLink.takeSnapshot() ?: return null
    val upright = uprightScaled(bitmap, 0, FramePolicy.EDGE)
    val jpeg = runCatching { bitmapToJpegBase64(upright, FramePolicy.QUALITY) }.getOrNull()
    if (upright !== bitmap) upright.recycle()
    bitmap.recycle()
    if (jpeg != null) countSnapshot()
    return jpeg
}

private var snapshots = 0L

/** Un envoi sur 20 journalisé (preuve que le pont lunettes → SAM tourne). */
private fun countSnapshot() {
    snapshots += 1
    if (snapshots % 20L == 0L) Log.i(TAG, "pont lunettes : $snapshots photos envoyées à la sonde")
}

private const val TAG = "GlassesBridge"
private const val BRIDGE_POLL_MS = 500L
