package dev.sam3vision.app.ihear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.CameraSurface
import dev.sam3vision.app.FirstPixelWatcher
import dev.sam3vision.app.IseePermissionFlow
import dev.sam3vision.app.LiveDistanceLayer
import dev.sam3vision.app.decodeScaledExact
import dev.sam3vision.app.missingPermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * La carte caméra du design (VISION) — extraite du pane pour garder les fichiers
 * courts. Géométrie de la vidéo : grande carte ≈ 84 % de la largeur, 22 % de la
 * hauteur, centrée à ~0,198 de l'écran ; le double tap pose la photo dedans,
 * le voile de pixels n'est que l'entrée de la carte (il s'efface, voir
 * [rememberPixelVeil]) et la chip REC tient en haut à gauche.
 *
 * C'est **l'unique** carte caméra de l'app : elle porte aussi la liaison caméra
 * ([rememberCameraGlue]), le câblage SAM ([IhearCameraWiring]) et les pastilles de
 * distance de la photo — plus aucune fenêtre caméra concurrente.
 */
@Composable
internal fun BoxWithConstraintsScope.VisionCard(
    state: IhearUiState,
    glue: CameraGlue,
    probe: LiveProbeState,
    viewModel: IhearViewModel,
    grid: PixelGrid,
    width: Dp,
    height: Dp,
) {
    val w = width * 0.84f
    val h = height * 0.22f
    val veil = rememberPixelVeil(state)
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .offset(y = height * 0.198f - h / 2)
            .size(w, h)
            .clip(RoundedCornerShape(26.dp))
            .border(3.dp, IseeSceneColors.cameraFrame, RoundedCornerShape(26.dp))
            .background(Color(0xFF14161B)),
    ) {
        CameraLive(glue, probe, viewModel, Modifier.fillMaxSize())
        state.photoPath?.let { path ->
            PhotoInside(path)
            // Une pastille par objet mesuré, posée sur la photo le temps qu'elle reste.
            LiveDistanceLayer(state.distanceLayer, topInset = 0.dp, withLabel = true)
        }
        PixelMosaic(grid, veil.value, circle = false, modifier = Modifier.fillMaxSize())
        if (state.recordingVideo) RecordingChip()
        CrossingChip(probe, Modifier.align(Alignment.BottomCenter)) { viewModel.toggleCrossing() }
        LiveDebugPanel(probe, Modifier.align(Alignment.TopStart), dense = true)
        ProbeNoticeCard(probe)
    }
}

/**
 * Le voile de pixels de la carte : à 1 quand la carte s'allume, effacé en
 * [PIXEL_VEIL_MS] pour rendre l'aperçu caméra net ; à 0 sur une photo (la photo du
 * double tap se montre telle quelle).
 *
 * Avant : la mosaïque restait opaque pendant tout l'enregistrement — la carte
 * n'était que 26×16 = 416 gros blocs (~39×32 px), et l'aperçu CameraX comme les
 * masques SAM affichés dans la même surface native étaient cachés dessous.
 *
 * Le voile est une **entrée**, jamais un état : il part de 1 et ne va que vers 0.
 * Il remontait à 1 dès que l'enregistrement s'arrêtait, et la carte redevenait la
 * mosaïque de gros blocs au lieu de montrer l'aperçu net — c'est ce que voyait
 * l'utilisateur en stoppant la vidéo pour poser une question.
 */
@Composable
private fun rememberPixelVeil(state: IhearUiState): Animatable<Float, AnimationVector1D> {
    val veil = remember { Animatable(1f) }
    LaunchedEffect(state.recordingVideo, state.photoPath) {
        if (state.recordingVideo && state.photoPath == null) {
            veil.animateTo(0f, tween(PIXEL_VEIL_MS))
        } else {
            veil.snapTo(0f)
        }
    }
    return veil
}

/** Durée d'effacement du voile de pixels à l'entrée de la carte (ms). */
internal const val PIXEL_VEIL_MS = 1_100

/** Notice courte en centre de carte quand aucun masque n'est là (réveil GPU, etc). */
@Composable
internal fun BoxScope.ProbeNoticeCard(probe: LiveProbeState) {
    liveProbeNotice(probe)?.let { notice ->
        Text(
            notice,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .background(Color(0xCC1A1F26), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** Aperçu vivant : masques SAM et notice « pourquoi la caméra ne voit rien ». */
@Composable
internal fun CameraLive(glue: CameraGlue, probe: LiveProbeState, viewModel: IhearViewModel, modifier: Modifier) {
    // SAM tourne pendant l'enregistrement et en mode Traversée : le câblage vit avec la carte.
    IhearCameraWiring(glue, viewModel, probe.frames)
    if (!glue.granted) {
        Text(
            "iSee needs the camera.",
            color = Color.White,
            fontSize = 12.sp,
            modifier = modifier.padding(12.dp),
        )
        return
    }
    AndroidView(
        factory = { ctx -> CameraSurface(ctx, glue.preview) },
        update = { surface -> surface.setMasks(probe.masks) },
        modifier = modifier,
    )
    LiveDistanceLayer(probe.distanceLayer, topInset = 0.dp, modifier = Modifier.fillMaxSize())
}

/** Photo du double tap : la même fenêtre sert de cadre, flash puis affichage. */
@Composable
private fun PhotoInside(path: String) {
    val flash = remember { Animatable(1f) }
    LaunchedEffect(path) {
        flash.snapTo(1f)
        flash.animateTo(0f, tween(PHOTO_FLASH_MS, easing = LinearEasing))
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeScaledExact(File(path)) }
    }
    Box(Modifier.fillMaxSize()) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Photo taken",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
    }
}

/** Point bleu « REC » sur la chip de la carte pendant l'enregistrement. */
@Composable
private fun RecordingChip() {
    Row(
        Modifier
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(IseeSceneColors.cameraChip, CircleShape),
        )
        Spacer(Modifier.size(5.dp))
        Text("REC", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Bouton du mode Traversée : pastille d'état (grise = coupé ou doute, rouge,
 * verte) et libellé. Posé en bas de carte, à côté du REC.
 */
@Composable
private fun BoxScope.CrossingChip(probe: LiveProbeState, modifier: Modifier, onToggle: () -> Unit) {
    val dot = when {
        !probe.crossingActive -> Color(0xFF808080)
        probe.crossing == dev.sam3vision.core.crossing.CrossingState.Green -> Color(0xFF2ECC71)
        probe.crossing == dev.sam3vision.core.crossing.CrossingState.Red -> Color(0xFFE74C3C)
        else -> Color(0xFFF1C40F)
    }
    Row(
        modifier
            .padding(8.dp)
            .background(Color(0x99000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.size(5.dp))
        Text(
            crossingButtonLabel(probe.crossingActive, probe.crossing),
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** La photo du double tap reste affichée un peu plus longtemps que le flash. */
internal const val PHOTO_FLASH_MS = 340

/* ————————————————— Liaison caméra et câblage SAM ————————————————— */

/** Permission + liaison caméra : un seul bloc, rappelé par le visuel. Composée en
 * permanence par le pane (caméra prête au premier geste), la vue ne vit que dans
 * la carte de la scène VISION. */
internal data class CameraGlue(
    val host: IhearCameraHost,
    val granted: Boolean,
    val failure: String?,
    /** Aperçu créé dès la liaison : la carte se contente de le montrer. */
    val preview: PreviewView,
)

/** La liaison caméra de l'écran : permission, aperçu permanent et actions du host. */
@Composable
internal fun rememberCameraGlue(viewModel: IhearViewModel): CameraGlue {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(cameraGranted(context)) }
    var failure by remember { mutableStateOf<String?>(null) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
    }
    // Premier lancement : la demande groupée centrale gère les trois permissions, la
    // carte ne demande rien elle-même ; une fois « handled », elle relit l'état réel.
    val firstRun = remember { missingPermissions(context).isNotEmpty() }
    val firstRunHandled = IseePermissionFlow.handled.value
    val host = remember { IhearCameraHost(context) }
    // L'aperçu est créé ici, **en permanence** : `bind()` exige une PreviewView déjà
    // attachée, et la carte n'existe qu'une fois l'enregistrement lancé. Sans ça,
    // le premier tap répondait « Camera not ready » (mesuré sur le téléphone).
    val preview = remember(host) { host.newPreviewView(context) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        if (!granted && !firstRun) askCamera.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(firstRun, firstRunHandled) {
        if (firstRun && firstRunHandled) granted = cameraGranted(context)
    }
    LaunchedEffect(granted) {
        if (granted) {
            failure = host.bind(owner)
            if (failure == null) viewModel.attachCamera(host.actions)
        }
    }
    DisposableEffect(Unit) {
        viewModel.attachCamera(host.actions)
        onDispose {
            host.release()
            viewModel.detachCamera()
        }
    }
    return CameraGlue(host, granted, failure, preview)
}

/**
 * Câblage de l'analyseur sur la sonde SAM : les frames (avec leur instant d'exposition,
 * seul point de départ honnête de la latence perçue) dans un sens, l'admission dans
 * l'autre, et le dernier poste de la chronologie posé au frame Compose suivant.
 */
@Composable
private fun IhearCameraWiring(glue: CameraGlue, viewModel: IhearViewModel, frames: Int) {
    LaunchedEffect(glue.host, viewModel) {
        // SAM ne voit que l'enregistrement — sauf en mode Traversée, qui analyse
        // sans enregistrer : hors ces deux cas, aucune frame n'est même convertie.
        glue.host.wantsFrame = { sending(viewModel) && viewModel.probe.wantsFrame() }
        glue.host.onFrame = { jpeg, captureAtNs ->
            if (sending(viewModel)) viewModel.probe.onFrame(jpeg, captureAtNs)
        }
    }
    FirstPixelWatcher(frames, frames > 0) { viewModel.probe.noteDrawn() }
}

/** Vrai quand les frames partent vers SAM : enregistrement ou mode Traversée. */
private fun sending(viewModel: IhearViewModel): Boolean =
    viewModel.state.value.recordingVideo || viewModel.probe.state.value.crossingActive

/** Vrai si la permission caméra est déjà accordée. */
fun cameraGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}