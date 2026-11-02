package dev.sam3vision.app.isee

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.glasses.GlassesBackgroundFrames
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.uprightScaled
import dev.sam3vision.core.distance.CropGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Alimente la sonde SAM depuis la rue des lunettes : quand le direct vit et que la sonde
 * veut une frame, on décode la poussée du flux hors écran, on l'encode comme le téléphone
 * (JPEG redressé [ISEE_FRAME_EDGE]/[ISEE_FRAME_QUALITY], 320/q55 — pas le 480/q65 du
 * partage aidant) et on l'envoie. Guidage, distances et voix suivent alors le même
 * chemin que le mode téléphone : parité par construction, sans toucher à
 * [Guidance][core.live.Guidance]. Le compteur de marches part aussi (même photo → Canny),
 * sinon la voix ne connaît jamais leur nombre.
 *
 * Plus aucune photo de la vue : l'ancien `takeSnapshot` était un `getBitmap` sur le fil UI
 * (3-15 ms bloquants par poussée, jusqu'à 5-11 fois/s). Le bitstream est désormais toujours
 * décodé en fond, y compris écran allumé — la focale est donc celle du flux plein, jamais
 * celle du recadrage « remplit la vue ».
 *
 * Piloté par les poussées du flux (conflaté : la plus fraîche gagne), jamais sondé :
 * l'ancien sondage ajoutait jusqu'à 500 ms à chaque envoi. [LiveProbe.wantsFrame] reste
 * le garde de cadence ; en guidage, [GuidanceGate] plafonne en plus à une frame toutes les
 * [GUIDANCE_FRAME_MS] (une image de rue toutes les 0,4 s suffit à suivre une marche
 * d'itinéraire, et le serveur respire pendant que la voix parle).
 *
 * Téléphone en veille : la poussée est décodée hors écran ([GlassesBackgroundFrames]) et
 * la sonde ne se tait pas jusqu'au réveil — même chemin que l'écran allumé, donc rien de
 * spécifique à préserver.
 */
@Composable
internal fun GlassesProbeWiring(probe: LiveProbe, glassesFeed: Boolean, guidance: Boolean) {
    val sampler = remember { GlassesBackgroundFrames() }
    DisposableEffect(sampler) {
        onDispose { sampler.release() }
    }
    // Lu dans la collecte sans relancer l'effet : un changement de guidage ne coupe pas le pont.
    val guiding = rememberUpdatedState(guidance)
    LaunchedEffect(probe, glassesFeed) {
        probe.setGlassesLive(glassesFeed)
        if (!glassesFeed) return@LaunchedEffect
        val gate = GuidanceGate()
        try {
            GlassesVideoLink.frames.collect { box ->
                if (box == null || !probe.wantsFrame()) return@collect
                if (!gate.admits(guiding.value, SystemClock.elapsedRealtime())) return@collect
                // Décodage, redressement, JPEG, Canny et envoi : tout en fond, comme le
                // fil d'analyse du téléphone.
                withContext(Dispatchers.Default) {
                    val decodeStart = SystemClock.elapsedRealtime()
                    val bitmap = sampler.bitmapOf(box) ?: return@withContext
                    val jpegStart = SystemClock.elapsedRealtime()
                    applyGlassesLens(probe, fromView = false)
                    val jpeg = bitmapToProbeJpeg(bitmap) ?: return@withContext
                    val sentAt = SystemClock.elapsedRealtime()
                    // Arrivée + séquence du flux : le poste `capt` mesure enfin le pont
                    // (décodage + JPEG), et la ligne rejoint le GlassesLink de même seq.
                    probe.onFrame(jpeg, box.frame.arrivedAtNs, box.seq)
                    countBridgeSplit(jpegStart - decodeStart, sentAt - jpegStart)
                }
            }
        } finally {
            probe.setGlassesLive(false)
        }
    }
}

/**
 * Porte de cadence du guidage : au plus une frame toutes les [GUIDANCE_FRAME_MS].
 * Hors guidage elle laisse tout passer — c'est alors [LiveProbe.wantsFrame] seul qui
 * décide. État minimal (un `Long`), aucune allocation par frame.
 */
private class GuidanceGate {
    private var lastAtMs = 0L

    /** La frame peut-elle partir maintenant ? Oui hors guidage, ou créneau de 400 ms échu. */
    fun admits(guidance: Boolean, nowMs: Long): Boolean {
        if (!guidance) return true
        // Horloge monotone : un recalage d'heure ne peut pas bloquer la porte.
        if (nowMs - lastAtMs < GUIDANCE_FRAME_MS) return false
        lastAtMs = nowMs
        return true
    }
}

/** Bitmap de la rue → JPEG d'envoi (hors fil UI) ; les bitmaps jetables sont recyclés aussitôt. */
private fun bitmapToProbeJpeg(bitmap: Bitmap): String? {
    // Parité téléphone (320/q55) : le 480/q65 coûtait 2,25× pixels à l'encodage.
    val upright = uprightScaled(bitmap, 0, ISEE_FRAME_EDGE)
    feedStairCounter(upright)
    val jpeg = runCatching { bitmapToJpegBase64(upright, ISEE_FRAME_QUALITY) }.getOrNull()
    if (upright !== bitmap) upright.recycle()
    bitmap.recycle()
    if (jpeg != null) countSnapshot()
    return jpeg
}

/**
 * Focale cohérente avec la photo envoyée. La vue n'est plus photographiée : le pont
 * appelle toujours `fromView = false`, et la focale flux D1 s'applique telle quelle.
 *
 * `internal` pour les tests (`LiveProbeCalibrationTest`) : la politique par
 * photo (vue = crop, fond = flux) y est pinnée en JVM, d'où la branche `fromView = true`
 * conservée bien que la production ne l'emprunte plus. Silencieux sans focale ni
 * géométrie : le pont ne tombe jamais pour ça.
 */
internal fun applyGlassesLens(probe: LiveProbe, fromView: Boolean) {
    val stream = GlassesVideoLink.glassesStreamLens ?: return
    if (!fromView) {
        probe.distances.setLens(stream)
        return
    }
    val geometry = GlassesVideoLink.snapshotGeometry() ?: return
    val crop = CropGeometry.fillViewCrop(geometry.videoW, geometry.videoH, geometry.viewW, geometry.viewH)
    val cropped = CropGeometry.afterCrop(stream, geometry.videoW, geometry.videoH, crop) ?: return
    probe.distances.setLens(cropped)
}

/**
 * Compteur de marches côté lunettes : la même photo redressée part au Canny
 * (miroir du `onLuma` téléphone), au plus une fois par seconde — le compteur
 * garde sa valeur 1,5 s (`FRESH_MS`), un Canny par frame est du CPU jeté
 * (~2-5 ms/frame). Silencieux en cas d'échec : le pont vers SAM ne doit jamais
 * tomber pour un compteur.
 */
private fun feedStairCounter(bitmap: Bitmap) {
    if (bitmap.width <= 0 || bitmap.height <= 0) return
    val nowMs = SystemClock.elapsedRealtime()
    if (!stairFeedDue(lastStairFeedMs, nowMs)) return
    lastStairFeedMs = nowMs
    runCatching {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        StairCannyCount.offerPixels(pixels, bitmap.width, bitmap.height)
    }
}

/**
 * Le Canny des marches est-il dû ? Oui quand la fenêtre [STAIR_FEED_MS] est
 * échue depuis [lastMs] — horloge monotone, comme la porte de guidage. Pur,
 * testé en JVM.
 */
internal fun stairFeedDue(lastMs: Long, nowMs: Long): Boolean =
    nowMs - lastMs >= STAIR_FEED_MS

/** Fenêtre du Canny des marches côté lunettes : 1 s (le compteur tient 1,5 s). */
internal const val STAIR_FEED_MS = 1_000L

private var snapshots = 0L

/** Dernier Canny des marches (ms monotones, `0` = jamais : le premier passe). */
private var lastStairFeedMs = 0L

/** Un envoi sur 20 journalisé (preuve que le pont lunettes → SAM tourne). */
private fun countSnapshot() {
    snapshots += 1
    if (snapshots % 20L == 0L) Log.i(TAG, "pont lunettes : $snapshots photos envoyées à la sonde")
}

/**
 * Décomposition du pont, même cadence que [countSnapshot] : « bridgesplit decode 3 ·
 * enc 24 » — `decode` = poussée → bitmap, `enc` = redressement + Canny + JPEG.
 * Invisible à `parse_isee_lat.py` (ni `latency`, ni `server:`), même tag [TAG].
 */
private fun countBridgeSplit(decodeMs: Long, encMs: Long) {
    if (snapshots % 20L == 0L) Log.i(TAG, "bridgesplit decode $decodeMs · enc $encMs")
}

/**
 * Cadence de guidage : une frame toutes les 400 ms (~2,5 fps). En guidage, la rue bouge
 * lentement (on regarde une direction, pas une scène) et la cadence de course de
 * [LiveProbe.wantsFrame] ne servait qu'à noyer le réseau pendant que la voix parle.
 */
private const val GUIDANCE_FRAME_MS = 400L

private const val TAG = "GlassesBridge"
