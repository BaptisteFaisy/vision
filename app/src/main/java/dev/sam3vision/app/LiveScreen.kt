package dev.sam3vision.app

import android.content.Context
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.sam3vision.core.distance.LensSpec
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Le téléphone envoie des frames de 512 px maximum, en JPEG léger (lien montant limité). */
const val LIVE_EDGE = 512
const val LIVE_JPEG_QUALITY = 65

/**
 * Mode Direct : aperçu caméra, frames poussées à [LiveViewModel], masques SAM 3.1 superposés,
 * questions au LLM pendant que le flux continue.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var attached by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var memoryOpen by remember { mutableStateOf(false) }
    val previewView = rememberLivePreview(context) { attached = it }

    LaunchedEffect(Unit) { viewModel.startFromSettings() }
    LiveCameraHost(viewModel, previewView, attached) { failure = it }
    // Dernier poste de la chronologie : la frame n'est comptée dessinée qu'après compose.
    // La clé est la liste de masques (neuve à chaque frame), pas la frame : deux scènes
    // identiques donnent deux `LiveFrame` égales.
    FirstPixelWatcher(state.overlays, state.overlays.isNotEmpty()) { viewModel.noteDrawn() }

    LiveStage(
        state = state,
        previewView = previewView,
        draft = draft,
        onDraftChange = { draft = it },
        failure = failure,
        onBack = onBack,
        onAsk = onAsk,
        onOpenSettings = onOpenSettings,
        onApplyPrompts = { viewModel.restartWith(draft) },
        onRecover = { viewModel.restartActive() },
        memoryOpen = memoryOpen,
        onToggleMemory = { memoryOpen = !memoryOpen },
        onFocus = viewModel::setFocus,
        onAskMemory = viewModel::askMemory,
        onVoiceStart = viewModel::startVoice,
        onVoiceStop = viewModel::stopVoice,
        onToggleSam = viewModel::toggleSam,
        onToggleTracker = viewModel::toggleTracker,
    )
}

/** Aperçu mémorisé, avec notification d'attachement (sinon l'analyseur n'est jamais lié). */
@Composable
private fun rememberLivePreview(context: Context, onAttached: (Boolean) -> Unit): PreviewView =
    remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = onAttached(true)
                override fun onViewDetachedFromWindow(view: android.view.View) = onAttached(false)
            })
        }
    }

/**
 * Cycle de vie de la caméra d'analyse : liée quand l'aperçu est à l'écran, déliée
 * à la sortie. Le calibrage optique lu au liaison part droit au ViewModel (distances).
 */
@Composable
private fun LiveCameraHost(
    viewModel: LiveViewModel,
    previewView: PreviewView,
    attached: Boolean,
    onFailure: (String) -> Unit,
) {
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    LaunchedEffect(attached) {
        if (!attached) return@LaunchedEffect
        runCatching {
            bindLiveCamera(context, previewView, executor, viewModel::applyLens) { proxy ->
                pushFrame(proxy, viewModel)
            }
        }.onFailure { onFailure("Caméra indisponible : ${it.message}") }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
}

/** Scène : aperçu, masques superposés, barre haute (état + concepts) et barre basse (question). */
@Composable
private fun LiveStage(
    state: LiveUiState,
    previewView: PreviewView,
    draft: String,
    onDraftChange: (String) -> Unit,
    failure: String?,
    onBack: () -> Unit,
    onAsk: () -> Unit,
    onOpenSettings: () -> Unit,
    onApplyPrompts: () -> Unit,
    onRecover: () -> Unit,
    memoryOpen: Boolean,
    onToggleMemory: () -> Unit,
    onFocus: (Int) -> Unit,
    onAskMemory: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onToggleSam: () -> Unit,
    onToggleTracker: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LiveCameraStage(state, previewView, onRecover)
        LiveTopBar(
            state = state,
            draft = draft,
            onDraftChange = onDraftChange,
            onApply = onApplyPrompts,
            failure = failure,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onToggleSam = onToggleSam,
            onToggleTracker = onToggleTracker,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        LiveFooter(
            state = state,
            memoryOpen = memoryOpen,
            onToggleMemory = onToggleMemory,
            onOpenChat = onAsk,
            onFocus = onFocus,
            onAskMemory = onAskMemory,
            onVoiceStart = onVoiceStart,
            onVoiceStop = onVoiceStop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Aperçu, masques et pastilles de distance : les masques vivent dans la même surface
 * native que l'aperçu (un `AndroidView` passe au-dessus du canevas Compose), les
 * pastilles sont composées après, donc au-dessus. Un tap relance un flux arrêté.
 */
@Composable
private fun LiveCameraStage(state: LiveUiState, previewView: PreviewView, onRecover: () -> Unit) {
    AndroidView(
        factory = { ctx -> CameraSurface(ctx, previewView) },
        update = { surface -> surface.setMasks(state.overlays) },
        modifier = Modifier.fillMaxSize(),
    )
    LiveDistanceLayer(state.distanceLayer)
    // Direct arrêté ou en erreur : un tap relance. Jamais pendant que le flux tourne.
    if (!state.running || state.error != null) RecoveryTap(onRecover)
}

/** Bas de l'écran : panneau mémoire (si ouvert) au-dessus de la barre d'actions. */
@Composable
private fun LiveFooter(
    state: LiveUiState,
    memoryOpen: Boolean,
    onToggleMemory: () -> Unit,
    onOpenChat: () -> Unit,
    onFocus: (Int) -> Unit,
    onAskMemory: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (memoryOpen) {
            LiveMemoryPanel(
                memory = state.memory,
                onFocus = onFocus,
                onAsk = onAskMemory,
                voice = state.voice,
                onVoiceStart = onVoiceStart,
                onVoiceStop = onVoiceStop,
                onClose = onToggleMemory,
            )
        }
        LiveBottomBar(state, memoryOpen, onToggleMemory, onOpenChat)
    }
}

/**
 * Zone transparente de récupération : quand le direct est arrêté ou en erreur, un tap sur
 * l'aperçu relance la session. Posée sous les bandeaux, qui gardent la priorité tactile.
 */
@Composable
private fun RecoveryTap(onRestart: () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onRestart() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "Touche l'écran pour relancer le direct",
            color = AppColors.text,
            fontSize = 13.sp,
            modifier = Modifier
                .background(AppColors.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Preview + analyseur « une frame à la fois », résolution capteur réduite.
 * [onLens] reçoit le champ de vision réel du capteur dès la liaison (les distances
 * en ont besoin ; `null` si le driver est muet).
 */
private suspend fun bindLiveCamera(
    context: Context,
    previewView: PreviewView,
    executor: ExecutorService,
    onLens: (LensSpec?) -> Unit,
    onImage: (ImageProxy) -> Unit,
) {
    val owner = previewView.findViewTreeLifecycleOwner() ?: error("Cycle de vie indisponible")
    val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(LIVE_EDGE, LIVE_EDGE), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build(),
        )
        .build()
        .also { it.setAnalyzer(executor) { proxy -> onImage(proxy) } }
    provider.unbindAll()
    val camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    onLens(lensSpecOf(camera.cameraInfo))
}

/**
 * ImageProxy → JPEG base64 en **une** passe de matrice (rotation + échelle), avec
 * l'instant d'exposition du capteur : c'est lui qui rend la latence perçue mesurable
 * de bout en bout. Les frames ne s'accumulent jamais : gate côté ViewModel.
 */
private fun pushFrame(proxy: ImageProxy, viewModel: LiveViewModel) {
    try {
        val upright = uprightScaled(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, LIVE_EDGE)
        viewModel.onFrame(bitmapToJpegBase64(upright, LIVE_JPEG_QUALITY), proxy.imageInfo.timestamp)
    } catch (_: Throwable) {
        // frame illisible : la suivante part tout de suite
    } finally {
        proxy.close()
    }
}
