package dev.sam3vision.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Vrai si la permission CAMERA est déjà accordée. */
fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun mainPost(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else Handler(Looper.getMainLooper()).post(block)
}

/**
 * Écran caméra. La liaison CameraX est refaite à chaque (ré)attachement de l'aperçu :
 * sans ça, revenir sur cet écran laissait `ImageCapture` non lié (« Not bound to a valid Camera »).
 */
@Composable
fun CaptureScreen(
    onPhoto: (File) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTap: (startNow: Boolean) -> Unit,
    onOpenLive: () -> Unit,
    onShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var attached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bindToken by remember { mutableStateOf(0) }
    var bound by remember { mutableStateOf(false) }
    val previewView = rememberPreviewView { attached = it }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        // Copie potentiellement lourde (image cloud) : hors du thread principal.
        if (uri != null) scope.launch { pickFromGallery(context, uri, onPhoto)?.let { error = it } }
    }

    // Écran affiché : on lance le réveil du GPU tout de suite (le temps de cadrer).
    LaunchedEffect(Unit) { onShown() }

    LaunchedEffect(granted, attached, bindToken) {
        if (!granted || !attached) return@LaunchedEffect
        bindCamera(context, previewView.findViewTreeLifecycleOwner(), previewView, imageCapture)
            .onSuccess { bound = true }
            .onFailure { error = it.message }
    }

    // Sortie de l'écran : la caméra est rendue (`bound` = `.get()` immédiat, jamais
    // d'initialisation bloquante ici) et le fil d'exécution est arrêté (fuite sinon).
    DisposableEffect(Unit) {
        onDispose {
            if (bound) runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
            executor.shutdown()
        }
    }

    CameraSurface(
        granted = granted,
        attached = attached,
        previewView = previewView,
        error = error,
        onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
        onCapture = {
            captureToFile(imageCapture, executor, context, onPhoto, onError = { message ->
                error = message
                bindToken += 1
            })
        },
        onGallery = { galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onSettings = onOpenSettings,
        onOpenTap = onOpenTap,
        onLive = onOpenLive,
    )
}

/** Aperçu + bandeau d'erreur + commandes (les trois boutons). */
@Composable
private fun CameraSurface(
    granted: Boolean,
    attached: Boolean,
    previewView: PreviewView,
    error: String?,
    onAllow: () -> Unit,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onOpenTap: (startNow: Boolean) -> Unit,
    onLive: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (granted) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        } else {
            PermissionGate(onAllow)
        }
        // Un tap sur l'aperçu lance le mode vidéo **et** son enregistrement ; les commandes
        // posées ensuite restent prioritaires (Compose livre le geste au plus haut).
        Box(
            Modifier
                .fillMaxSize()
                .tapOnce { onOpenTap(true) },
        )
        GestureHint(Modifier.align(Alignment.TopCenter))
        error?.let { StatusBanner(it, AppColors.danger, Modifier.align(Alignment.TopCenter)) }
        CaptureControls(
            modifier = Modifier.align(Alignment.BottomCenter),
            ready = granted && attached,
            onCapture = onCapture,
            onGallery = onGallery,
            onSettings = onSettings,
            onOpenTap = { onOpenTap(false) }, // le bouton ouvre l'écran : l'utilisateur se place
            onLive = onLive,
        )
    }
}

/** PreviewView mémorisé, avec notification de (d')attachement pour piloter la liaison caméra. */
@Composable
private fun rememberPreviewView(onAttached: (Boolean) -> Unit): PreviewView {
    val context = LocalContext.current
    return remember(context) {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Sans flux, la surface montre ce fond sombre au lieu du blanc système.
            setBackgroundColor(AppColors.background.toArgb())
            addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: android.view.View) = onAttached(true)
                override fun onViewDetachedFromWindow(view: android.view.View) = onAttached(false)
            })
        }
    }
}

/** Copie l'image choisie puis l'analyse ; la copie disque se fait hors du thread principal. */
private suspend fun pickFromGallery(context: Context, uri: android.net.Uri, onPhoto: (File) -> Unit): String? {
    val target = newCacheFile(context)
    val copied = withContext(Dispatchers.IO) { copyUriToCache(context, uri, target) }
    if (copied) {
        onPhoto(target)
        return null
    }
    return "Impossible de copier l'image choisie."
}

private suspend fun bindCamera(
    context: Context,
    owner: LifecycleOwner?,
    previewView: PreviewView,
    imageCapture: ImageCapture,
): Result<Unit> {
    if (owner == null) return Result.failure(IllegalStateException("Cycle de vie indisponible pour lier la caméra"))
    val provider = try {
        withContext(Dispatchers.IO) { ProcessCameraProvider.getInstance(context).get() }
    } catch (cancel: CancellationException) {
        throw cancel // effet annulé par recomposition : pas une panne de caméra
    } catch (e: Exception) {
        return Result.failure(IllegalStateException("Aucune caméra accessible : ${e.message}"))
    }
    return runCatching {
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        Unit
    }
}

private fun captureToFile(
    imageCapture: ImageCapture,
    executor: ExecutorService,
    context: Context,
    onPhoto: (File) -> Unit,
    onError: (String) -> Unit,
) {
    val target = newCacheFile(context)
    val options = ImageCapture.OutputFileOptions.Builder(target).build()
    imageCapture.takePicture(
        options,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = mainPost { onPhoto(target) }

            override fun onError(exception: ImageCaptureException) = mainPost { onError("Capture impossible : ${exception.message}") }
        },
    )
}

/** Commandes d'action sous l'aperçu : capture pleine largeur, galerie et réglages. */
@Composable
private fun CaptureControls(
    modifier: Modifier,
    ready: Boolean,
    onCapture: () -> Unit,
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    onOpenTap: () -> Unit,
    onLive: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BigButton("Photographier", ready, AppColors.primary, onCapture)
        BigButton("Direct (vidéo)", ready, AppColors.success, onLive)
        HalfRow(
            left = { BigButton("Vidéo (tap)", true, AppColors.success, onOpenTap, 0.5f) },
            right = { BigButton("Galerie", true, AppColors.surfaceHigh, onGallery, 0.5f) },
        )
        BigButton("Réglages", true, AppColors.surfaceHigh, onSettings)
    }
}

/** Deux boutons côte à côte, poids égaux. */
@Composable
private fun HalfRow(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f)) { left() }
        Box(Modifier.weight(1f)) { right() }
    }
}

/**
 * Rappel visible des deux gestes de l'écran d'accueil : sans lui, personne ne devine que
 * l'appui long parle et que le tap lance la vidéo. Posé sous la barre d'état, il ne masque
 * ni l'aperçu ni les commandes.
 */
@Composable
private fun GestureHint(modifier: Modifier = Modifier) {
    Text(
        text = "Tap : vidéo · Appui long : parler",
        color = Color.White,
        fontSize = 13.sp,
        modifier = modifier
            .statusBarsPadding()
            .padding(top = 12.dp, start = 12.dp, end = 12.dp)
            .background(AppColors.surfaceHigh.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
