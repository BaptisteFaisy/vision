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
import dev.sam3vision.app.speechClient
import dev.sam3vision.core.net.HttpSignalsClient
import dev.sam3vision.core.settings.Settings

/**
 * Écran navigation de la pile : guidage direct (ou confirmation si la
 * session l'ouvre), piloté par l'état ([VoiceNavUiState]) — un seul écran,
 * deux visages.
 *
 * - boutons via [VoiceNavViewModel] (le composable ne gère aucune coroutine) ;
 * - dictée sur place ([VoiceNavHoldButton], même moteur que l'accueil iSee) :
 *   « oui »/« non »/destination sur la confirmation, destination ou ordre
 *   stop en guidage — tout l'écran est faisable à la voix, sans retour ;
 * - quitter l'écran (retour) = arrêter : pas de guidage invisible — le repos
 *   de session coupe le moteur ([IseeViewModel.dismissVoiceNav]) ;
 * - mise en fond (home, verrouillage) en guidage = maintien via le service
 *   W1 ([GuidanceLifecycle]) ; au retour, l'écran reprend sur l'état intact.
 */
@Composable
fun VoiceNavRoute(isee: IseeViewModel, settings: Settings) {
    val nav = remember { VoiceNavViewModel(isee.voiceNav()) }
    val hold = remember { VoiceNavHold(engine = ::speechClient) }
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
    VoiceNavScreen(state, nav, isee, hold, settings)
}

/**
 * Le visage courant de la navigation : un état, un écran. Extrait de
 * [VoiceNavRoute] pour tenir sous le plafond de 50 lignes du garde-fou —
 * aucun effet de bord ici, seulement le rendu.
 */
@Composable
private fun VoiceNavScreen(
    state: VoiceNavUiState,
    nav: VoiceNavViewModel,
    isee: IseeViewModel,
    hold: VoiceNavHold,
    settings: Settings,
) {
    when (val current = state) {
        is VoiceNavUiState.Idle -> Box(Modifier.fillMaxSize())
        is VoiceNavUiState.Confirming -> DestinationConfirmScreen(
            state = current,
            onConfirm = nav::confirmDestination,
            onCancel = nav::cancelConfirmation,
            hold = hold,
            settings = settings,
            onHoldTranscript = { nav.session().onVoiceTranscript(it) },
            // W3 : ajouter `onSubmitText = nav::submitTypedDestination` à son merge.
        )
        is VoiceNavUiState.Guiding -> GuidingRoute(
            isee = isee,
            state = current,
            onStop = nav::stopGuidance,
            hold = hold,
            settings = settings,
            onHoldTranscript = { nav.session().onVoiceTranscript(it) },
        )
        // « Nouvelle destination » remet la session au repos (la dictée est à
        // portée, contrat d'état de VoiceNavSession) ; « Fermer » referme
        // l'arrivée puis rend l'accueil iSee (guide utilisateur, §3 Arriver).
        is VoiceNavUiState.Arrived -> ArrivedScreen(
            state = current,
            onNewDestination = { nav.session().dismissArrived() },
            onClose = {
                nav.session().dismissArrived()
                isee.dismissVoiceNav()
            },
        )
    }
}

/**
 * Guidage actif : écran + mini-carte (tracé + feux). Les feux viennent de
 * `/signals` (même base que l'itinéraire) ; sans URL, liste vide.
 */
@Composable
private fun GuidingRoute(
    isee: IseeViewModel,
    state: VoiceNavUiState.Guiding,
    onStop: () -> Unit,
    hold: VoiceNavHold,
    settings: Settings,
    onHoldTranscript: suspend (String) -> Boolean,
) {
    val signalsClient = remember { HttpSignalsClient(isee::routeBaseUrl) }
    GuidanceScreen(
        state = state,
        onStop = onStop,
        hold = hold,
        settings = settings,
        onHoldTranscript = onHoldTranscript,
        navSnapshot = isee::debugNavSnapshot,
        fetchSignals = { bounds ->
            if (isee.routeBaseUrl().isBlank()) {
                emptyList()
            } else {
                signalsClient.signals(bounds).getOrElse { emptyList() }
            }
        },
    )
}
