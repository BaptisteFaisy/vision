package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sam3vision.app.isee.IseeViewModel

/**
 * Écran navigation de la pile : guidage direct (ou confirmation si la
 * session l'ouvre), piloté par l'état ([VoiceNavUiState]) — un seul écran,
 * deux visages.
 *
 * - boutons via [VoiceNavViewModel] (le composable ne gère aucune coroutine) ;
 * - dictée (« redicter », « arrête le guidage ») : retour à l'accueil iSee,
 *   où l'appui long habituel reprend (la session reste ouverte, l'ordre y
 *   est consommé et l'écran se rouvre) ;
 * - quitter l'écran (retour) = arrêter : pas de guidage invisible — le repos
 *   de session coupe le moteur ([IseeViewModel.dismissVoiceNav]) ;
 * - mise en fond (home, verrouillage) en guidage = maintien via le service
 *   W1 ([GuidanceLifecycle]) ; au retour, l'écran reprend sur l'état intact.
 */
@Composable
fun VoiceNavRoute(isee: IseeViewModel, onClose: () -> Unit) {
    val nav = remember { VoiceNavViewModel(isee.voiceNav()) }
    val state by nav.state.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(nav) {
        onDispose { isee.dismissVoiceNav() }
    }
    DisposableEffect(lifecycle, nav) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    if (GuidanceLifecycle.wantsBackground(nav.state.value)) {
                        GuidanceLifecycle.startBackground(context)
                    }
                }
                Lifecycle.Event.ON_RESUME -> GuidanceLifecycle.stopBackground(context)
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    when (val current = state) {
        is VoiceNavUiState.Idle -> Box(Modifier.fillMaxSize())
        is VoiceNavUiState.Confirming -> DestinationConfirmScreen(
            state = current,
            onConfirm = nav::confirmDestination,
            onCancel = nav::cancelConfirmation,
            onVoiceEntry = onClose,
            // W3 : ajouter `onSubmitText = nav::submitTypedDestination` à son merge.
        )
        is VoiceNavUiState.Guiding -> GuidanceScreen(
            state = current,
            onStop = nav::stopGuidance,
            onVoiceEntry = onClose,
        )
        // W5 : ajouter `is VoiceNavUiState.Arrived -> ArrivedScreen(...)` à son merge.
    }
}
