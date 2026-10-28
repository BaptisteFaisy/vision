package dev.sam3vision.app.voicenav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.sam3vision.app.isee.IseeViewModel

/**
 * Écran navigation de la pile : confirmation puis guidage, pilotés par
 * l'état de session ([VoiceNavUiState]) — un seul écran, deux visages.
 *
 * - boutons via [VoiceNavViewModel] (le composable ne gère aucune coroutine) ;
 * - dictée (« redicter », « arrête le guidage ») : retour à l'accueil iSee,
 *   où l'appui long habituel reprend (la session reste ouverte, l'ordre y
 *   est consommé et l'écran se rouvre) ;
 * - quitter l'écran (retour) = arrêter : pas de guidage invisible — le repos
 *   de session coupe le moteur ([IseeViewModel.dismissVoiceNav]).
 */
@Composable
fun VoiceNavRoute(isee: IseeViewModel, onClose: () -> Unit) {
    val nav = remember { VoiceNavViewModel(isee.voiceNav()) }
    val state by nav.state.collectAsState()
    DisposableEffect(nav) {
        onDispose { isee.dismissVoiceNav() }
    }
    when (val current = state) {
        is VoiceNavUiState.Idle -> Box(Modifier.fillMaxSize())
        is VoiceNavUiState.Confirming -> DestinationConfirmScreen(
            state = current,
            onConfirm = nav::confirmDestination,
            onCancel = nav::cancelConfirmation,
            onVoiceEntry = onClose,
        )
        is VoiceNavUiState.Guiding -> GuidanceScreen(
            state = current,
            onStop = nav::stopGuidance,
            onVoiceEntry = onClose,
        )
    }
}
