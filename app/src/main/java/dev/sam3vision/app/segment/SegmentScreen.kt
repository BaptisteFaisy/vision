package dev.sam3vision.app.segment

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.depth.ModelDownloader
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.segment.SegmentMask
import dev.sam3vision.core.segment.Segmenter
import dev.sam3vision.core.segment.WalkAdvice
import dev.sam3vision.core.segment.WalkDirection
import dev.sam3vision.core.segment.WalkGuide
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Démo segmentation : aperçu caméra + conseil de marche (flèche) + cadence.
 * Vert = on marche, rouge = on ne marche pas, voies jaunes. La couche rapide
 * on-device qui complète SAM 3.1 : mêmes frames que le direct, 0 GPU Modal,
 * et chaque masque s'exporte en prompt ([SegmentMask.toPrompt]).
 *
 * Mesure : une ligne `twinlite:` dans logcat toutes les 20 segmentations
 * (couverture, `ms` d'inférence, `ips` effectifs) + ligne d'état à l'écran.
 */
@Composable
fun SegmentScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val host = remember { SegmentCameraHost(context) }
    val view = remember { host.newPreviewView(context) }
    val segmenter = remember { mutableStateOf<Segmenter?>(null) }
    val meter = remember { LiveFpsMeter() }
    val busy = remember { AtomicBoolean(false) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    var advice by remember { mutableStateOf(WalkAdvice(WalkDirection.STOP, 0f, 0f, 0f)) }
    var frameSize by remember { mutableStateOf(640 to 360) }
    var status by remember { mutableStateOf("chargement du modèle…") }
    var error by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0f) }

    BackHandler { onBack() }
    DisposableEffect(host) {
        host.wantsFrame = { !busy.get() && segmenter.value != null }
        host.onFrame = { bitmap ->
            onCameraFrame(scope, segmenter.value, bitmap, busy) { done ->
                val gap = meter.gapAt(SystemClock.elapsedRealtime())
                fps = LiveFpsMeter.fpsFor(gap, fps)
                overlay = done.overlay
                advice = done.advice
                frameSize = bitmap.width to bitmap.height
                runs += 1
                val delegate = segmenter.value?.delegateName ?: "?"
                status = statusLine(done, fps, delegate)
                logRun(runs, done, fps, delegate)
                busy.set(false)
            }
        }
        onDispose {
            host.onFrame = null
            scope.launch(Dispatchers.IO) { host.unbind() }
            host.release()
            segmenter.value?.close()
            segmenter.value = null
        }
    }
    LaunchedEffect(Unit) {
        segmenter.value = prepareSegment(context, host, view, owner, onStatus = { status = it })
        if (segmenter.value == null) error = status
    }
    SegmentLayout(view, overlay, advice, frameSize, error ?: status, onBack)
}

/**
 * Frame traitée : overlay prêt à afficher + masques (couvertures) + conseil.
 * Échec = tout `null`, conseil STOP (inconnu ⇒ on ne marche pas).
 */
private data class FrameDone(
    val overlay: ImageBitmap?,
    val masks: Pair<SegmentMask, SegmentMask>?,
    val advice: WalkAdvice,
    val ms: Long,
    /** Phases (diagnostic perf) : pixels, segmentation, overlay+conseil. */
    val phasePx: Long,
    val phaseSeg: Long,
    val phaseOvl: Long,
)

/** Une frame caméra → segmentation (jetée si une inférence est en vol). */
private fun onCameraFrame(
    scope: CoroutineScope,
    engine: Segmenter?,
    bitmap: Bitmap,
    busy: AtomicBoolean,
    onDone: (FrameDone) -> Unit,
) {
    if (engine == null || !busy.compareAndSet(false, true)) return
    scope.launch(Dispatchers.Default) { runFrame(engine, bitmap, onDone) }
}

/**
 * Modèle (téléchargé si absent) + session LiteRT + caméra liée ; `null` =
 * [onStatus] porte la raison affichable (erreur modèle ou caméra).
 */
private suspend fun prepareSegment(
    context: android.content.Context,
    host: SegmentCameraHost,
    view: PreviewView,
    owner: LifecycleOwner,
    onStatus: (String) -> Unit,
): Segmenter? {
    onStatus("téléchargement du modèle…")
    val ensured = ModelDownloader.ensureSpec(
        context.filesDir,
        SegmentModels.TWINLITE,
        dir = SegmentModelStore.DIR,
        onProgress = { done, total -> onStatus("modèle… ${done * 100 / total.coerceAtLeast(1)} %") },
    )
    if (ensured.isFailure) {
        onStatus("modèle : ${ensured.exceptionOrNull()?.message}")
        return null
    }
    val engine = withContext(Dispatchers.Default) { LiteRtTwinLiteSegmenter.open(context) }
    if (engine == null) {
        onStatus("ouverture du modèle impossible")
        return null
    }
    val bindError = host.bind(owner, view)
    if (bindError != null) {
        onStatus(bindError)
        engine.close()
        return null
    }
    onStatus("en route…")
    return engine
}

/** Aperçu + surcouche de masques + flèche de marche + ligne d'état. */
@Composable
private fun SegmentLayout(
    view: PreviewView,
    overlay: ImageBitmap?,
    advice: WalkAdvice,
    frameSize: Pair<Int, Int>,
    status: String,
    onBack: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            Button(onClick = onBack) { Text("Retour") }
            Text(text = status, modifier = Modifier.padding(start = 12.dp, top = 12.dp))
        }
        Box(Modifier.fillMaxSize()) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
            MaskOverlay(overlay)
            Text(
                text = advice.direction.arrow,
                fontSize = 96.sp,
                color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/** Une frame redressée → pixels → segmentation → rappel sur le fil principal. */
private suspend fun runFrame(
    engine: Segmenter,
    bitmap: Bitmap,
    onDone: (FrameDone) -> Unit,
) {
    val t0 = SystemClock.elapsedRealtime()
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val t1 = SystemClock.elapsedRealtime()
    val result = engine.segment(argb, bitmap.width, bitmap.height).getOrNull()
    val t2 = SystemClock.elapsedRealtime()
    val ms = t2 - t0
    // Overlay (230 kpx) et conseil calculés ici : le fil principal ne fait
    // qu'afficher, il ne calcule plus.
    val done = if (result == null) {
        FrameDone(null, null, WalkAdvice(WalkDirection.STOP, 0f, 0f, 0f), ms, 0, 0, 0)
    } else {
        val t3 = SystemClock.elapsedRealtime()
        val overlay = masksToBitmap(result.driveable, result.lanes).asImageBitmap()
        val advice = WalkGuide.directionFor(result.driveable)
        val t4 = SystemClock.elapsedRealtime()
        FrameDone(
            overlay,
            result.driveable to result.lanes,
            advice,
            result.inferenceMs,
            t1 - t0,
            t2 - t1,
            t4 - t3,
        )
    }
    withContext(Dispatchers.Main) { onDone(done) }
}

/** Ligne d'état : conseil de marche, couvertures, inférence, cadence, délégué. */
private fun statusLine(done: FrameDone, fps: Float, delegate: String): String {
    val masks = done.masks
    if (masks == null) return String.format(Locale.FRANCE, "échec · %d ms · %.1f ips", done.ms, fps)
    return String.format(
        Locale.FRANCE,
        "%s %s · praticable %.0f %% · %d ms · %.1f ips · %s",
        done.advice.direction.arrow,
        done.advice.direction.label,
        masks.first.coverage() * 100,
        done.ms,
        fps,
        delegate,
    )
}

/** Une ligne de mesure toutes les 20 segmentations (pas une trace complète). */
private fun logRun(runs: Int, done: FrameDone, fps: Float, delegate: String) {
    if (runs % 20 != 0) return
    val masks = done.masks
    val cover = masks?.let {
        String.format(Locale.US, "drive=%.0f%% lanes=%.1f%%", it.first.coverage() * 100, it.second.coverage() * 100)
    } ?: "échec"
    val walk = String.format(
        Locale.US,
        "walk=%s L=%.2f C=%.2f R=%.2f",
        done.advice.direction.name,
        done.advice.left,
        done.advice.center,
        done.advice.right,
    )
    val phases = "pipe=${done.phasePx}+${done.phaseSeg}+${done.phaseOvl}"
    Log.i("sam3vision", "twinlite: $cover, ${done.ms} ms, " + String.format(Locale.US, "%.1f", fps) + " ips [$delegate] $walk $phases")
}

/** Overlay précalculé en fond : le canevas ne fait que l'étirer. */
@Composable
private fun MaskOverlay(image: ImageBitmap?) {
    Canvas(Modifier.fillMaxSize()) {
        if (image == null) return@Canvas
        drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
    }
}

/**
 * Les deux masques → un bitmap ARGB : vert = on marche, rouge = on ne marche
 * pas (translucide, la scène reste lisible), voies jaunes par-dessus.
 * Boucle serrée sans lambda (230 kpx par frame) : mêmes pixels, ~10× moins
 * cher que les `getOrElse` par pixel.
 */
private fun masksToBitmap(drive: SegmentMask, lanes: SegmentMask): Bitmap {
    val w = drive.width
    val h = drive.height
    require(lanes.width == w && lanes.height == h) { "masques désalignés" }
    val driveBytes = drive.bytes
    val laneBytes = lanes.bytes
    val pixels = IntArray(w * h)
    for (i in pixels.indices) {
        pixels[i] = when {
            laneBytes[i].toInt() != 0 -> 0xE6FFFF00.toInt()
            driveBytes[i].toInt() != 0 -> 0x6E00FF00.toInt()
            else -> 0x66FF0000.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
