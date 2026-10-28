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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.depth.ModelDownloader
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.segment.SegmentMask
import dev.sam3vision.core.segment.Segmenter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Démo segmentation : aperçu caméra + zone praticable (vert) et voies (rouge)
 * + cadence. La couche rapide on-device qui complète SAM 3.1 : mêmes frames
 * que le direct, 0 GPU Modal, et chaque masque s'exporte en prompt
 * ([SegmentMask.toPrompt]).
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
    var masks by remember { mutableStateOf<Pair<SegmentMask, SegmentMask>?>(null) }
    var frameSize by remember { mutableStateOf(640 to 360) }
    var status by remember { mutableStateOf("chargement du modèle…") }
    var error by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0f) }

    BackHandler { onBack() }
    DisposableEffect(host) {
        host.wantsFrame = { !busy.get() && segmenter.value != null }
        host.onFrame = { bitmap ->
            onCameraFrame(scope, segmenter.value, bitmap, busy) { result, ms ->
                val gap = meter.gapAt(SystemClock.elapsedRealtime())
                fps = LiveFpsMeter.fpsFor(gap, fps)
                masks = result
                frameSize = bitmap.width to bitmap.height
                runs += 1
                status = statusLine(result, ms, fps)
                logRun(runs, result, ms, fps)
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
    SegmentLayout(view, masks, frameSize, error ?: status, onBack)
}

/** Une frame caméra → segmentation (jetée si une inférence est en vol). */
private fun onCameraFrame(
    scope: CoroutineScope,
    engine: Segmenter?,
    bitmap: Bitmap,
    busy: AtomicBoolean,
    onDone: (Pair<SegmentMask, SegmentMask>?, Long) -> Unit,
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

/** Aperçu + surcouche de masques + ligne d'état : une entrée par état. */
@Composable
private fun SegmentLayout(
    view: PreviewView,
    masks: Pair<SegmentMask, SegmentMask>?,
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
            MaskOverlay(masks)
        }
    }
}

/** Une frame redressée → pixels → segmentation → rappel sur le fil principal. */
private suspend fun runFrame(
    engine: Segmenter,
    bitmap: Bitmap,
    onDone: (Pair<SegmentMask, SegmentMask>?, Long) -> Unit,
) {
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val started = SystemClock.elapsedRealtime()
    val result = engine.segment(argb, bitmap.width, bitmap.height).getOrNull()
    val ms = SystemClock.elapsedRealtime() - started
    withContext(Dispatchers.Main) {
        val masks = result?.let { it.driveable to it.lanes }
        onDone(masks, result?.inferenceMs ?: ms)
    }
}

/** Ligne d'état : couvertures, inférence, cadence effective. */
private fun statusLine(masks: Pair<SegmentMask, SegmentMask>?, ms: Long, fps: Float): String {
    if (masks == null) return String.format(Locale.FRANCE, "échec · %d ms · %.1f ips", ms, fps)
    return String.format(
        Locale.FRANCE,
        "praticable %.0f %% · voies %.1f %% · %d ms · %.1f ips",
        masks.first.coverage() * 100,
        masks.second.coverage() * 100,
        ms,
        fps,
    )
}

/** Une ligne de mesure toutes les 20 segmentations (pas une trace complète). */
private fun logRun(runs: Int, masks: Pair<SegmentMask, SegmentMask>?, ms: Long, fps: Float) {
    if (runs % 20 != 0) return
    val cover = masks?.let {
        String.format(Locale.US, "drive=%.0f%% lanes=%.1f%%", it.first.coverage() * 100, it.second.coverage() * 100)
    } ?: "échec"
    Log.i("sam3vision", "twinlite: $cover, $ms ms, " + String.format(Locale.US, "%.1f", fps) + " ips")
}

/** Masques remis à l'échelle du canevas : praticable vert, voies rouge. */
@Composable
private fun MaskOverlay(masks: Pair<SegmentMask, SegmentMask>?) {
    val image = remember(masks) { masks?.let { masksToBitmap(it.first, it.second) }?.asImageBitmap() }
    Canvas(Modifier.fillMaxSize()) {
        if (image == null) return@Canvas
        drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
    }
}

/** Les deux masques → un bitmap ARGB (voies par-dessus le praticable). */
private fun masksToBitmap(drive: SegmentMask, lanes: SegmentMask): Bitmap {
    val pixels = IntArray(drive.width * drive.height)
    for (i in pixels.indices) {
        pixels[i] = when {
            lanes.bytes.getOrElse(i) { 0 }.toInt() != 0 -> 0xC8FF0000.toInt()
            drive.bytes.getOrElse(i) { 0 }.toInt() != 0 -> 0x6E00FF00.toInt()
            else -> 0x00000000
        }
    }
    return Bitmap.createBitmap(pixels, drive.width, drive.height, Bitmap.Config.ARGB_8888)
}
