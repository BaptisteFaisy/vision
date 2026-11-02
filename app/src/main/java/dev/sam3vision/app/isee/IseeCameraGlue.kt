package dev.sam3vision.app.isee

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.IseePermissionFlow
import dev.sam3vision.app.missingPermissions
import dev.sam3vision.app.permissionResumeTick
import kotlinx.coroutines.delay

/** Permission + liaison caméra : un seul bloc, rappelé par le visuel. Composée en
 * permanence par le pane (caméra prête au premier geste), la vue ne vit que dans
 * la carte de la scène VISION. */
internal data class CameraGlue(
    val host: IseeCameraHost,
    val granted: Boolean,
    /** Raison du dernier échec de liaison, `null` si la caméra est liée. Lu par la carte. */
    val failure: String?,
    /** Aperçu créé dès la liaison : la carte se contente de le montrer. */
    val preview: PreviewView,
)

/**
 * L'état de la liaison caméra de l'écran : permission **relue**, échec affiché,
 * compteur d'essais et compte de reprises.
 *
 * Un objet plutôt que quatre `remember` : `rememberCameraGlue` et son effet
 * restent ainsi sous la limite de 50 lignes par fonction.
 */
internal class CameraGlueState(granted: Boolean) {
    var granted by mutableStateOf(granted)
    var failure by mutableStateOf<String?>(null)
    var failures by mutableStateOf(0)
    var retry by mutableStateOf(0)
}

/** La liaison caméra de l'écran : permission, aperçu permanent et actions du host. */
@Composable
internal fun rememberCameraGlue(viewModel: IseeViewModel): CameraGlue {
    val context = LocalContext.current
    // Android ne prévient personne quand une permission est accordée ou révoquée
    // depuis les Réglages : on relit l'état réel à chaque retour au premier plan.
    val tick = permissionResumeTick()
    val state = remember { CameraGlueState(cameraGranted(context)) }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        state.granted = it
    }
    // Premier lancement : la demande groupée centrale gère les trois permissions, la
    // carte ne demande rien elle-même ; une fois « handled », elle relit l'état réel.
    val firstRun = remember(tick) { missingPermissions(context).isNotEmpty() }
    val firstRunHandled = IseePermissionFlow.handled.value
    val host = remember { IseeCameraHost(context) }
    // L'aperçu est créé ici, **en permanence** : `bind()` exige une PreviewView déjà
    // attachée, et la carte n'existe qu'une fois l'enregistrement lancé. Sans ça,
    // le premier tap répondait « Camera not ready » (mesuré sur le téléphone).
    val preview = remember(host) { host.newPreviewView(context) }
    LaunchedEffect(Unit) {
        if (!state.granted && !firstRun) askCamera.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(tick, firstRun, firstRunHandled) { state.granted = cameraGranted(context) }
    CameraBinding(state, host, LocalLifecycleOwner.current, viewModel)
    DisposableEffect(host) {
        viewModel.attachCamera(host.actions)
        onDispose {
            viewModel.setCameraActive(false)
            host.release()
            viewModel.detachCamera()
        }
    }
    return CameraGlue(host, state.granted, state.failure, preview)
}

/**
 * Le cycle de liaison : une tentative, puis les reprises décidées par [cameraGate]
 * (délais croissants de [nextRetryDelayMs], plafonnés). Une caméra prise par une
 * autre app est signalée par CameraX et reprise ici — jamais dans une boucle folle.
 */
@Composable
private fun CameraBinding(
    state: CameraGlueState,
    host: IseeCameraHost,
    owner: LifecycleOwner,
    viewModel: IseeViewModel,
) {
    LaunchedEffect(host) { host.onCameraClosed = { state.retry++ } }
    LaunchedEffect(state.granted, state.retry) {
        if (!state.granted) {
            host.unbind()
            state.failures = 0
            state.failure = null
            viewModel.setCameraActive(false)
            return@LaunchedEffect
        }
        when (cameraGate(state.granted, host.bound, host.closed, host.recording, state.failures)) {
            CameraGate.HOLD -> viewModel.setCameraActive(host.bound)
            CameraGate.GIVE_UP -> viewModel.setCameraActive(false)
            else -> bindWithRetry(state, host, owner, viewModel)
        }
    }
}

/**
 * Lie la caméra jusqu'à réussite ou plafond d'essais. Rend la raison du dernier
 * échec, `null` si la caméra est liée ; l'état de la carte suit chaque tentative.
 */
private suspend fun bindWithRetry(
    state: CameraGlueState,
    host: IseeCameraHost,
    owner: LifecycleOwner,
    viewModel: IseeViewModel,
) {
    while (true) {
        val reason = host.bind(owner)
        if (reason == null) {
            state.failures = 0
            state.failure = null
            viewModel.attachCamera(host.actions)
            viewModel.setCameraActive(true)
            return
        }
        state.failures += 1
        state.failure = reason
        viewModel.setCameraActive(false)
        // Borné : `null` = MP4 en cours, ou plafond atteint (le retour au premier
        // plan relancera une tentative, pas cette boucle).
        delay(nextRetryDelayMs(state.failures, host.recording) ?: return)
    }
}
