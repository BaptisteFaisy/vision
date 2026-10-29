package dev.sam3vision.app.isee

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
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
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import dev.sam3vision.app.glasses.GlassesVideoLink
import dev.sam3vision.app.IseePermissionFlow
import dev.sam3vision.app.LiveDistanceLayer
import dev.sam3vision.app.missingPermissions
import kotlinx.coroutines.delay
import java.io.File

/**
 * La carte caméra du design (VISION) — extraite du pane pour garder les fichiers
 * courts. Géométrie de la vidéo : grande carte ≈ 84 % de la largeur, 22 % de la
 * hauteur, centrée à ~0,198 de l'écran ; l'aperçu direct y vit pendant
 * l'enregistrement, le voile de pixels n'est que l'entrée de la carte (il
 * s'efface, voir [rememberPixelVeil]) et la chip REC tient en haut à gauche.
 *
 * C'est **l'unique** carte caméra de l'app : elle porte aussi la liaison caméra
 * ([rememberCameraGlue]), le câblage SAM ([IseeCameraWiring]) et les pastilles de
 * distance de la photo — plus aucune fenêtre caméra concurrente.
 */
@Composable
internal fun BoxWithConstraintsScope.VisionCard(
    state: IseeUiState,
    glue: CameraGlue,
    probe: LiveProbeState,
    viewModel: IseeViewModel,
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
        PixelMosaic(grid, veil.value, circle = false, modifier = Modifier.fillMaxSize())
        if (state.recordingVideo) RecordingChip()
        CrossingChip(probe, Modifier.align(Alignment.BottomCenter)) { viewModel.toggleCrossing() }
        LiveDebugPanel(probe, Modifier.align(Alignment.TopStart), dense = true)
        ProbeNoticeCard(probe)
    }
}

/**
 * Le voile de pixels de la carte : à 1 quand la carte s'allume, effacé en
 * [PIXEL_VEIL_MS] pour rendre l'aperçu caméra net.
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
private fun rememberPixelVeil(state: IseeUiState): Animatable<Float, AnimationVector1D> {
    val veil = remember { Animatable(1f) }
    LaunchedEffect(state.recordingVideo) {
        if (state.recordingVideo) {
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
/**
 * Le seau de lecture locale du feu, un par écran : la boîte vient de la sonde
 * (serveur), la couleur est relue sur chaque frame caméra, ≤ 150 ms.
 */
@Composable
internal fun rememberCrossingPump(viewModel: IseeViewModel): CrossingLocalLight {
    val scope = rememberCoroutineScope()
    return remember(viewModel) {
        CrossingLightPump.install(scope, Dispatchers.Default) { viewModel.probe.state.value.crossingBox }
    }
}

@Composable
internal fun CameraLive(glue: CameraGlue, probe: LiveProbeState, viewModel: IseeViewModel, modifier: Modifier) {
    // SAM tourne quand la caméra est active (enregistrement et Traversée aussi) : le câblage vit avec la carte.
    val pump = rememberCrossingPump(viewModel)
    IseeCameraWiring(glue, viewModel, probe.frames, pump)
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
    SteeringLayer(probe.steering)
    val twinUi by viewModel.twinlite.ui.collectAsState()
    TwinLiteLayer(twinUi)
    CrossingOnVideo(probe, pump)
}

/**
 * Le feu sur la vidéo : la **couleur** vient de la lecture locale quand elle
 * existe ([CrossingLocalLight], ≤ 150 ms), la **boîte** du dernier passage
 * serveur. Un feu ne se déplace pas : une position vieille de 400 ms reste
 * juste, alors qu'une couleur vieille de 400 ms ne l'est plus.
 */
@Composable
internal fun CrossingOnVideo(probe: LiveProbeState, pump: CrossingLocalLight) {
    if (!probe.crossingActive) return
    val local by pump.ui.collectAsState()
    CrossingOverlayLayer(
        active = true,
        box = local?.box ?: probe.crossingBox,
        state = local?.state ?: probe.crossing,
        frameWidth = probe.width,
        frameHeight = probe.height,
    )
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
    // Mêmes teintes que le cadre posé sur la vidéo : une seule source (CrossingOverlayLayout).
    val dot = Color(crossingAccent(probe.crossingActive, probe.crossing))
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


/* ————————————————— Liaison caméra et câblage SAM ————————————————— */

/** Permission + liaison caméra : un seul bloc, rappelé par le visuel. Composée en
 * permanence par le pane (caméra prête au premier geste), la vue ne vit que dans
 * la carte de la scène VISION. */
internal data class CameraGlue(
    val host: IseeCameraHost,
    val granted: Boolean,
    val failure: String?,
    /** Aperçu créé dès la liaison : la carte se contente de le montrer. */
    val preview: PreviewView,
)

/** La liaison caméra de l'écran : permission, aperçu permanent et actions du host. */
@Composable
internal fun rememberCameraGlue(viewModel: IseeViewModel): CameraGlue {
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
    val host = remember { IseeCameraHost(context) }
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
            viewModel.setCameraActive(failure == null)
        } else {
            viewModel.setCameraActive(false)
        }
    }
    DisposableEffect(Unit) {
        viewModel.attachCamera(host.actions)
        onDispose {
            viewModel.setCameraActive(false)
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
private fun IseeCameraWiring(
    glue: CameraGlue,
    viewModel: IseeViewModel,
    frames: Int,
    pump: CrossingLocalLight,
) {
    LaunchedEffect(glue.host, viewModel) {
        // SAM voit la caméra dès qu'elle est active (l'enregistrement et la Traversée
        // envoient aussi) : hors ces cas, aucune frame n'est même convertie.
        // Quand les lunettes vivent, elles nourrissent seules la sonde (pont
        // GlassesProbeWiring) : mélanger les deux vues brouillerait le guidage.
        // (Lu au fil de l'eau : l'effet ne redémarre pas, une valeur collectée
        // une fois resterait gelée.)
        glue.host.wantsFrame = {
            if (GlassesVideoLink.live.value) false
            else (sending(viewModel) && viewModel.probe.wantsFrame()) || picturing(viewModel)
        }
        glue.host.onFrame = { jpeg, captureAtNs ->
            if (!GlassesVideoLink.live.value) {
                // La même frame part au proche : sa page montre ce que voit iSee.
                SharedCameraFrame.offer(jpeg)
                if (sending(viewModel)) {
                    viewModel.probe.onFrame(jpeg, captureAtNs)
                    viewModel.twinlite.offerFrame(jpeg)
                    // Couleur du feu relue ici, sur la frame la plus fraîche (≤ 150 ms).
                    if (viewModel.probe.state.value.crossingActive) pump.offerFrame(jpeg)
                }
                if (picturing(viewModel)) viewModel.picto.onFrame(jpeg)
            }
        }
    }
    // Carte retirée (fin de la scène) : plus d'image à proposer au partage.
    DisposableEffect(glue.host) {
        onDispose { SharedCameraFrame.clear() }
    }
    FirstPixelWatcher(frames, frames > 0) { viewModel.probe.noteDrawn() }
}

/** Vrai quand les frames partent vers SAM : caméra active, enregistrement ou Traversée. */
private fun sending(viewModel: IseeViewModel): Boolean = sendingToSam(
    cameraActive = viewModel.state.value.cameraActive,
    recordingVideo = viewModel.state.value.recordingVideo,
    crossingActive = viewModel.probe.state.value.crossingActive,
)

/**
 * Vrai quand les frames partent au matcher picto : des repères sont armés et
 * la vidéo tourne — le téléphone seul, jamais les lunettes ni le réseau.
 */
private fun picturing(viewModel: IseeViewModel): Boolean =
    viewModel.picto.armed() && viewModel.state.value.recordingVideo

/**
 * Porte d'envoi vers SAM, pure et testée : la caméra active allume la carte, et
 * sans caméra ni enregistrement ni Traversée, rien ne part (carte éteinte).
 */
internal fun sendingToSam(cameraActive: Boolean, recordingVideo: Boolean, crossingActive: Boolean): Boolean =
    cameraActive || recordingVideo || crossingActive

/** Vrai si la permission caméra est déjà accordée. */
fun cameraGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
}