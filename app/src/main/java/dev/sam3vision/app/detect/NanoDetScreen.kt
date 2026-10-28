package dev.sam3vision.app.detect

import android.graphics.Bitmap
import android.graphics.Paint
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.depth.ModelDownloader
import dev.sam3vision.app.live.LiveFpsMeter
import dev.sam3vision.core.detect.DetectBox
import dev.sam3vision.core.detect.Detector
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Démo NanoDet : aperçu caméra + boîtes locales + cadence. La couche rapide
 * on-device qui complète SAM 3.1 : mêmes frames que le direct, 0 GPU Modal,
 * et chaque boîte s'exporte en prompt ([DetectBox.toSamPrompt]).
 *
 * Mesure : une ligne `nanodet:` dans logcat toutes les 20 détections
 * (`obj`, `ms` d'inférence, `ips` effectifs) + ligne d'état à l'écran.
 */
@Composable
fun NanoDetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val host = remember { NanoDetCameraHost(context) }
    val view = remember { host.newPreviewView(context) }
    val detector = remember { mutableStateOf<Detector?>(null) }
    val meter = remember { LiveFpsMeter() }
    val busy = remember { AtomicBoolean(false) }
    var boxes by remember { mutableStateOf<List<DetectBox>>(emptyList()) }
    var frameSize by remember { mutableStateOf(320 to 320) }
    var status by remember { mutableStateOf("chargement du modèle…") }
    var error by remember { mutableStateOf<String?>(null) }
    var runs by remember { mutableStateOf(0) }
    var fps by remember { mutableStateOf(0f) }

    BackHandler { onBack() }
    DisposableEffect(host) {
        host.wantsFrame = { !busy.get() && detector.value != null }
        host.onFrame = { bitmap ->
            onCameraFrame(scope, detector.value, bitmap, busy) { result, ms ->
                val gap = meter.gapAt(SystemClock.elapsedRealtime())
                fps = LiveFpsMeter.fpsFor(gap, fps)
                boxes = result
                frameSize = bitmap.width to bitmap.height
                runs += 1
                status = statusLine(result.size, ms, fps)
                logRun(runs, result, ms, fps)
                busy.set(false)
            }
        }
        onDispose {
            host.onFrame = null
            scope.launch(Dispatchers.IO) { host.unbind() }
            host.release()
            detector.value?.close()
            detector.value = null
        }
    }
    LaunchedEffect(Unit) {
        detector.value = prepareNanoDet(context, host, view, owner, onStatus = { status = it })
        if (detector.value == null) error = status
    }
    NanoDetLayout(view, boxes, frameSize, error ?: status, onBack)
}

/** Une frame caméra → détection (jetée si une inférence est en vol). */
private fun onCameraFrame(
    scope: CoroutineScope,
    engine: Detector?,
    bitmap: Bitmap,
    busy: AtomicBoolean,
    onDone: (List<DetectBox>, Long) -> Unit,
) {
    if (engine == null || !busy.compareAndSet(false, true)) return
    scope.launch(Dispatchers.Default) { runFrame(engine, bitmap, onDone) }
}

/**
 * Modèle (téléchargé si absent) + session ORT + caméra liée ; `null` =
 * [onStatus] porte la raison affichable (erreur modèle ou caméra).
 */
private suspend fun prepareNanoDet(
    context: android.content.Context,
    host: NanoDetCameraHost,
    view: PreviewView,
    owner: LifecycleOwner,
    onStatus: (String) -> Unit,
): Detector? {
    onStatus("téléchargement du modèle…")
    val ensured = ModelDownloader.ensureSpec(
        context.filesDir,
        DetectModels.NANODET,
        onProgress = { done, total -> onStatus("modèle… ${done * 100 / total.coerceAtLeast(1)} %") },
        dir = DetectModelStore.DIR,
    )
    if (ensured.isFailure) {
        onStatus("modèle : ${ensured.exceptionOrNull()?.message}")
        return null
    }
    val engine = withContext(Dispatchers.Default) { OnnxNanoDetDetector.open(context) }
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

/** Aperçu + surcouche de boîtes + ligne d'état : une entrée par état. */
@Composable
private fun NanoDetLayout(
    view: PreviewView,
    boxes: List<DetectBox>,
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
            DetectionOverlay(boxes, frameSize.first, frameSize.second)
        }
    }
}

/** Une frame redressée → pixels → détection → rappel sur le fil principal. */
private suspend fun runFrame(
    engine: Detector,
    bitmap: Bitmap,
    onDone: (List<DetectBox>, Long) -> Unit,
) {
    val argb = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(argb, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val started = SystemClock.elapsedRealtime()
    val result = engine.detect(argb, bitmap.width, bitmap.height).getOrNull()
    val ms = SystemClock.elapsedRealtime() - started
    withContext(Dispatchers.Main) { onDone(result?.boxes.orEmpty(), result?.inferenceMs ?: ms) }
}

/** Ligne d'état : objets, inférence, cadence effective. */
private fun statusLine(objects: Int, ms: Long, fps: Float): String =
    String.format(Locale.FRANCE, "%d obj · %d ms · %.1f ips", objects, ms, fps)

/** Une ligne de mesure toutes les 20 détections (pas une trace complète). */
private fun logRun(runs: Int, boxes: List<DetectBox>, ms: Long, fps: Float) {
    if (runs % 20 != 0) return
    val top = boxes.maxByOrNull { it.score }
    Log.i(
        "sam3vision",
        "nanodet: ${boxes.size} obj, $ms ms, " +
            String.format(Locale.US, "%.1f", fps) + " ips" +
            (top?.let { " (top ${it.labelName} ${String.format(Locale.US, "%.2f", it.score)})" } ?: ""),
    )
}

/** Boîtes + étiquettes par-dessus l'aperçu, remises à l'échelle du canevas. */
@Composable
private fun DetectionOverlay(boxes: List<DetectBox>, frameWidth: Int, frameHeight: Int) {
    Canvas(Modifier.fillMaxSize()) {
        if (boxes.isEmpty() || frameWidth <= 0 || frameHeight <= 0) return@Canvas
        val sx = size.width / frameWidth
        val sy = size.height / frameHeight
        boxes.forEach { drawDetection(it, sx, sy) }
    }
}

private val labelPaint = Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 36f
    isAntiAlias = true
}

private val labelBackPaint = Paint().apply {
    color = android.graphics.Color.argb(180, 180, 0, 0)
}

/** Un rectangle + son étiquette, dans l'échelle du canevas. */
private fun DrawScope.drawDetection(box: DetectBox, sx: Float, sy: Float) {
    val left = box.x1 * sx
    val top = box.y1 * sy
    val width = (box.x2 - box.x1) * sx
    val height = (box.y2 - box.y1) * sy
    drawRect(Color.Red, Offset(left, top), Size(width, height), style = Stroke(3f))
    val label = "${box.labelName} ${String.format(Locale.US, "%.2f", box.score)}"
    val w = labelPaint.measureText(label)
    drawContext.canvas.nativeCanvas.drawRect(left, top - 44f, left + w + 16f, top, labelBackPaint)
    drawContext.canvas.nativeCanvas.drawText(label, left + 8f, top - 10f, labelPaint)
}
