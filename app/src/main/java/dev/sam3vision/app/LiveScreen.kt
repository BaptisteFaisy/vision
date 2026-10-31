package dev.sam3vision.app

import android.content.Context
import android.graphics.Bitmap
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sam3vision.app.isee.CrossingOverlayLayer
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.sam3vision.app.live.setFocus
import dev.sam3vision.app.media.Nv21JpegEncoder
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.live.FrameTimings
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    var draft by rememberSaveable { mutableStateOf("") }
    var memoryOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val previewView = rememberLivePreview(context) { attached = it }

    LaunchedEffect(Unit) { viewModel.startFromSettings(HomePresetRequest.consume()) }
    ForegroundGate(viewModel)
    LiveCameraHost(viewModel, previewView, attached) { failure = it }
    // Dernier poste de la chronologie : la frame n'est comptée dessinée qu'après compose.
    // La clé est la liste de masques (neuve à chaque frame), pas la frame : deux scènes
    // identiques donnent deux `LiveFrame` égales.
    FirstPixelWatcher(state.overlays, state.overlays.isNotEmpty()) { viewModel.noteDrawn() }

    LiveStage(
        state = state,
        pixel = viewModel.pixel.stages.collectAsState().value,
        previewView = previewView,
        draft = draft,
        onDraftChange = { draft = it },
        failure = failure,
        onBack = onBack, onAsk = onAsk,
        onOpenSettings = onOpenSettings,
        onApplyPrompts = { viewModel.restartWith(draft) },
        onPreset = { viewModel.restartWith(it.joinToString(", ")) },
        onRecover = { viewModel.restartActive() },
        memoryOpen = memoryOpen,
        onToggleMemory = { memoryOpen = !memoryOpen },
        onFocus = viewModel::setFocus,
        onAskMemory = viewModel::askMemory,
        onVoiceStart = viewModel::startVoice,
        onVoiceStop = viewModel::stopVoice,
        onToggleSam = viewModel::toggleSam,
        onToggleTracker = viewModel::toggleTracker,
        onToggleTraverse = { viewModel.setTraverseActive(!state.traverseActive) },
        onToggleLocal = { scope.launch { viewModel.local.toggle() } },
        localFrame = viewModel.local.frame,
        pictoPng = viewModel.asker::pngOf,
    )
}

/** Aperçu mémorisé, avec notification d'attachement (sinon l'analyseur n'est jamais lié). */
@Composable
private fun rememberLivePreview(context: Context, onAttached: (Boolean) -> Unit): PreviewView =
    remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Sans flux (liaison en cours, caméra coupée), la surface montre ce fond
            // sombre au lieu du blanc de la fenêtre système.
            setBackgroundColor(AppColors.background.toArgb())
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = onAttached(true)
                override fun onViewDetachedFromWindow(view: android.view.View) = onAttached(false)
            })
        }
    }

/**
 * Coupe le flux SAM dès que l'app quitte le premier plan (`ON_PAUSE`, plus fiable que
 * `onStop` sur ce téléphone) et le relance au retour : sans ça, des frames en
 * arrière-plan rallument la carte après `/release`.
 */
@Composable
private fun ForegroundGate(viewModel: LiveViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onBackground()
                Lifecycle.Event.ON_RESUME -> viewModel.onForeground()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
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
    val encoder = remember { Nv21JpegEncoder() }
    var bound by remember { mutableStateOf(false) }
    LaunchedEffect(attached) {
        if (!attached) return@LaunchedEffect
        runCatching {
            bindLiveCamera(context, previewView, executor, viewModel::applyLens) { proxy ->
                pushFrame(proxy, viewModel, encoder)
            }
        }.onSuccess { bound = true }
            .onFailure { onFailure("Camera unavailable: ${it.message}") }
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
            viewModel.local.reset()
            // Le fournisseur est déjà initialisé si on a lié : `.get()` est immédiat.
            // Sans liaison, l'initialiser ici bloquerait le thread principal pour rien.
            if (bound) runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }
}

/** Scène : aperçu, masques superposés, barre haute (état + concepts) et barre basse (question). */
@Composable
private fun LiveStage(
    state: LiveUiState,
    pixel: FrameTimings,
    previewView: PreviewView,
    draft: String,
    onDraftChange: (String) -> Unit,
    failure: String?, onBack: () -> Unit,
    onAsk: () -> Unit, onOpenSettings: () -> Unit,
    onApplyPrompts: () -> Unit, onPreset: (List<String>) -> Unit,
    onRecover: () -> Unit,
    memoryOpen: Boolean,
    onToggleMemory: () -> Unit,
    onFocus: (Int) -> Unit,
    onAskMemory: (String) -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    onToggleSam: () -> Unit,
    onToggleTracker: () -> Unit, onToggleTraverse: () -> Unit,
    onToggleLocal: () -> Unit, localFrame: StateFlow<Bitmap?>, pictoPng: (String) -> ByteArray?,
) {
    Box(Modifier.fillMaxSize()) {
        LiveCameraStage(state, previewView, localFrame, onRecover, pictoPng)
        LiveTopBar(
            state = state,
            pixel = pixel,
            draft = draft,
            onDraftChange = onDraftChange,
            onApply = onApplyPrompts, onPreset = onPreset,
            failure = failure,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
            onToggleSam = onToggleSam,
            onToggleTracker = onToggleTracker, onToggleTraverse = onToggleTraverse,
            onToggleLocal = onToggleLocal,
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
 * Aperçu, masques, calque local et pastilles de distance : les masques vivent dans la
 * même surface native que l'aperçu (un `AndroidView` passe au-dessus du canevas
 * Compose), les pastilles sont composées après, donc au-dessus. Un tap relance un flux
 * arrêté.
 *
 * La surface native unique reçoit **soit** l'image du calque (30 ips), **soit** les
 * masques du serveur — jamais les deux (contrat de fusion §2.3). Le calque est collecté
 * ici et nulle part ailleurs : les bandeaux ne se recomposent pas à cette cadence. Direct
 * arrêté : rien n'est affiché, l'image locale est périmée.
 */
@Composable
private fun LiveCameraStage(
    state: LiveUiState,
    previewView: PreviewView,
    localFrame: StateFlow<Bitmap?>,
    onRecover: () -> Unit,
    pictoPng: (String) -> ByteArray?,
) {
    val local by localFrame.collectAsState()
    AndroidView(
        factory = { ctx -> CameraSurface(ctx, previewView) },
        update = { surface -> surface.setMasks(overlayLayers(state.overlays, if (state.running) local else null)) },
        modifier = Modifier.fillMaxSize(),
    )
    LiveDistanceLayer(state.distanceLayer)
    // Feu piéton du mode Traversée : cadre + étiquette, posés comme les masques.
    CrossingOverlayLayer(
        active = state.traverseActive,
        box = state.crossingBox,
        state = state.crossing,
        frameWidth = state.frame?.width ?: 0,
        frameHeight = state.frame?.height ?: 0,
    )
    // Repères reconnus : cadres jaunes + noms, et le premier en grand.
    LivePictoOverlay(state.pictoOverlay)
    LivePictoBig(state.pictoOverlay, pictoPng)
    // Boîtes prédites (T8) : couche vide tant que la prédiction est éteinte — l'overlay
    // reste alors celui d'avant (les masques serveur seuls).
    LivePredictionOverlay(state.prediction)
    // Chemins centraux : trajectoire + point exact de chaque objet suivi — couche
    // vide tant qu'aucune piste, l'overlay reste alors celui d'avant.
    LiveTrailOverlay(state.trails)
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
            "Tap the screen to restart the live",
            color = AppColors.text,
            fontSize = 13.sp,
            modifier = Modifier
                .background(AppColors.surface.copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/**
 * Aperçu plein capteur + analyseur « une frame à la fois », réduit à [LIVE_EDGE].
 * L'aperçu a sa propre résolution ([previewResolutionSelector]) : sinon l'analyse le
 * tirait vers le bas et la carte affichait des aplats.
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
    val owner = previewView.findViewTreeLifecycleOwner() ?: error("Lifecycle unavailable")
    val provider = withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    val preview = Preview.Builder()
        .setResolutionSelector(previewResolutionSelector())
        .build()
        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
    val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(
            // Repli « plus proche en dessous » comme iSee : un capteur qui dépasse 512 px
            // forçait une réduction coûteuse, en dessous l'encodeur rend le buffer tel quel.
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(LIVE_EDGE, LIVE_EDGE), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
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
 * ImageProxy → JPEG base64 **NV21 direct** (rotation et réduction dans la copie des
 * plans : zéro bitmap par frame), avec l'instant d'exposition du capteur : c'est lui qui
 * rend la latence perçue mesurable de bout en bout. Les frames ne s'accumulent jamais :
 * gate côté ViewModel. Repli sur l'ancien chemin `Bitmap` si le buffer n'est pas en YUV
 * 4:2:0.
 */
private fun pushFrame(proxy: ImageProxy, viewModel: LiveViewModel, encoder: Nv21JpegEncoder) {
    try {
        // Porte d'encodage (motif `IseeCameraPane`) : aucun consommateur (réseau, calque,
        // traversée, questions) ne veut la frame — on économise le JPEG (le `finally` ferme).
        if (!viewModel.wantsFrame()) return
        // NV21 direct (zéro bitmap par frame) **et** luminance du calque local :
        // le plan Y du NV21 est déjà tourné et réduit, donc c'est la même géométrie que
        // l'ancien bitmap `uprightScaled` — sans le payer.
        val frame = encoder.encodeFrame(proxy, LIVE_EDGE, viewModel.adaptive.jpegQuality)
        if (frame != null) {
            viewModel.onFrame(frame.jpegBase64, proxy.imageInfo.timestamp, frame.luma)
        } else {
            // Repli (buffer non YUV 4:2:0) : la luminance vient du bitmap déjà tourné.
            val upright = uprightScaled(proxy.toBitmap(), proxy.imageInfo.rotationDegrees, LIVE_EDGE)
            val luma = viewModel.local.lumaFor(upright)
            viewModel.onFrame(bitmapToJpegBase64(upright, viewModel.adaptive.jpegQuality), proxy.imageInfo.timestamp, luma)
        }
    } catch (_: Throwable) {
        // frame illisible : la suivante part tout de suite
    } finally {
        proxy.close()
    }
}
