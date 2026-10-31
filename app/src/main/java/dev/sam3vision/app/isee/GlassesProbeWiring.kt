package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.uprightScaled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alimente la sonde SAM depuis la rue des lunettes : quand le direct vit et que la sonde
 * veut une frame, on photographie la rue affichée (même image que l'utilisateur voit),
 * on l'encode comme le téléphone (JPEG 320 q55 redressé) et on l'envoie. Guidage,
 * distances et voix suivent alors le même chemin que le mode téléphone : parité par
 * construction, sans toucher à [Guidance][core.live.Guidance]. Le compteur de marches
 * part aussi (même photo → Canny), sinon la voix ne connaît jamais leur nombre.
 *
 * Piloté par les poussées du flux (conflaté : la plus fraîche gagne), jamais sondé :
 * l'ancien sondage ajoutait jusqu'à 500 ms à chaque envoi. [LiveProbe.wantsFrame] reste
 * le seul garde (créneau libre + cadence échue), donc le serveur ne voit pas plus de
 * frames qu'avant — il les voit plus tôt.
 */
@Composable
internal fun GlassesProbeWiring(probe: LiveProbe, glassesLive: Boolean) {
    LaunchedEffect(probe, glassesLive) {
        if (!glassesLive) return@LaunchedEffect
        GlassesVideoLink.frames.collect { box ->
            if (box == null || !probe.wantsFrame()) return@collect
            // La photo vient de la vue (fil UI) ; tout le reste — redressement, JPEG,
            // Canny, sonde — tourne en fond, comme le fil d'analyse du téléphone.
            val bitmap = GlassesVideoLink.takeSnapshot() ?: return@collect
            withContext(Dispatchers.Default) {
                val jpeg = bitmapToProbeJpeg(bitmap) ?: return@withContext
                probe.onFrame(jpeg, SystemClock.elapsedRealtimeNanos())
            }
        }
    }
}

/** Bitmap de la rue → JPEG d'envoi (hors fil UI) ; les bitmaps jetables sont recyclés aussitôt. */
private fun bitmapToProbeJpeg(bitmap: Bitmap): String? {
    val upright = uprightScaled(bitmap, 0, FramePolicy.EDGE)
    feedStairCounter(upright)
    val jpeg = runCatching { bitmapToJpegBase64(upright, FramePolicy.QUALITY) }.getOrNull()
    if (upright !== bitmap) upright.recycle()
    bitmap.recycle()
    if (jpeg != null) countSnapshot()
    return jpeg
}

/**
 * Compteur de marches côté lunettes : la même photo redressée part au Canny
 * (miroir du `onLuma` téléphone). Silencieux en cas d'échec : le pont vers SAM
 * ne doit jamais tomber pour un compteur.
 */
private fun feedStairCounter(bitmap: Bitmap) {
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    runCatching {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        StairCannyCount.offerPixels(pixels, bitmap.width, bitmap.height)
    }
}

private var snapshots = 0L

/** Un envoi sur 20 journalisé (preuve que le pont lunettes → SAM tourne). */
private fun countSnapshot() {
    snapshots += 1
    if (snapshots % 20L == 0L) Log.i(TAG, "pont lunettes : $snapshots photos envoyées à la sonde")
}

private const val TAG = "GlassesBridge"
