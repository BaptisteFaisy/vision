package dev.sam3vision.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.app.glasses.GlassesEvent
import dev.sam3vision.app.ihear.IhearHome
import dev.sam3vision.app.ihear.IhearViewModel
import dev.sam3vision.app.tap.TapCaptureScreen
import dev.sam3vision.core.glasses.CaptureAction

/** Routes de l'app (navigation par état, sans dépendance navigation-compose). */
sealed interface Route {
    /** Page d'accueil iSee : chat plein écran + fenêtre caméra flottante. */
    data object Ihear : Route
    data object Capture : Route
    data object Analysis : Route
    data object Chat : Route
    data object Live : Route
    data object Settings : Route

    /**
     * Mode vidéo (tap court) : vidéo → SAM 3.1, annonces ElevenLabs.
     * [autoStart] = lancé par un tap sur l'aperçu de l'accueil : la session démarre
     * d'elle-même, sans deuxième tap.
     */
    data class Tap(val autoStart: Boolean = false) : Route
}

/**
 * Racine de navigation : iSee en page d'accueil (chat + fenêtre caméra),
 * puis les écrans d'origine. Les gestes racine (IhearRootLayout) pilotent la
 * caméra et le chat via [IhearViewModel] quand iSee est à l'écran.
 */
@Composable
fun AppNav(
    viewModel: AppViewModel,
    ihear: IhearViewModel,
    glasses: GlassesController,
    liveViewModel: LiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    var stack by remember { mutableStateOf(listOf<Route>(Route.Ihear)) }
    val route = stack.first()
    val push: (Route) -> Unit = { stack = listOf(it) + stack }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.drop(1) }
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        // Les lunettes poussent leurs photos directement dans la navigation.
        GlassesBridge(glasses = glasses, viewModel = viewModel, push = push)

        when (route) {
            Route.Ihear -> IhearHome(
                viewModel = ihear,
                onOpenSettings = { push(Route.Settings) },
            )
            Route.Capture -> CaptureRoute(viewModel, push)
            Route.Analysis -> AnalysisRoute(state, viewModel, push, pop)
            Route.Live -> LiveRoute(viewModel, liveViewModel, push, pop)
            Route.Chat -> ChatRoute(state, viewModel, pop)
            is Route.Tap -> TapCaptureScreen(state.settings, pop, route.autoStart)
            Route.Settings -> SettingsRoute(
                viewModel = viewModel,
                state = state,
                glasses = glasses,
                pop = pop,
                onOpenLive = { push(Route.Live) },
                onOpenClassic = { push(Route.Capture) },
            )
        }

        state.dictation.overlay?.let { overlay ->
            DictationOverlay(overlay = overlay, onDismiss = viewModel::dismissOverlay)
        }
    }
}

/** Une photo prise sur les lunettes s'analyse comme une photo prise à la main. */
@Composable
private fun GlassesBridge(glasses: GlassesController, viewModel: AppViewModel, push: (Route) -> Unit) {
    LaunchedEffect(glasses) {
        glasses.events.collect { event ->
            if (event is GlassesEvent.PhotoTaken) {
                viewModel.onPhoto(event.file)
                push(if (event.action == CaptureAction.ASK) Route.Chat else Route.Analysis)
            }
        }
    }
}

/** Accueil classique : photo → analyse, ouverture des réglages, mode direct. */
@Composable
private fun CaptureRoute(viewModel: AppViewModel, push: (Route) -> Unit) {
    CaptureScreen(
        onPhoto = { file ->
            viewModel.onPhoto(file)
            push(Route.Analysis)
        },
        onOpenSettings = { push(Route.Settings) },
        onOpenTap = { startNow ->
            viewModel.stopSpeaking() // aucune voix du chat ne doit couvrir les annonces tap
            push(Route.Tap(autoStart = startNow))
        },
        onOpenLive = { push(Route.Live) },
    )
}

@Composable
private fun AnalysisRoute(
    state: UiState,
    viewModel: AppViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    AnalysisScreen(
        state = state,
        onBack = pop,
        onOpenChat = { push(Route.Chat) },
        onToggleMasks = viewModel::toggleMasks,
        onRetry = viewModel::retryAnalysis,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onOpenSettings = { push(Route.Settings) },
    )
}

/** Direct : le flux a son propre ViewModel ; une question part au chat avec sa frame. */
@Composable
private fun LiveRoute(
    viewModel: AppViewModel,
    liveViewModel: LiveViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    LiveScreen(
        viewModel = liveViewModel,
        onBack = { liveViewModel.stop(); pop() },
        onAsk = {
            viewModel.useLiveFrame(liveViewModel.latestFrameB64())
            push(Route.Chat)
        },
        onOpenSettings = { push(Route.Settings) },
    )
}

@Composable
private fun ChatRoute(state: UiState, viewModel: AppViewModel, pop: () -> Unit) {
    ChatScreen(
        state = state,
        onBack = pop,
        onAsk = viewModel::ask,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onStartDictation = viewModel::startDictation,
        onStopDictation = viewModel::stopDictation,
    )
}

/** Réglages : délègue au ViewModel et ferme l'écran après enregistrement. */
@Composable
private fun SettingsRoute(
    viewModel: AppViewModel,
    state: UiState,
    glasses: GlassesController,
    pop: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
) {
    SettingsScreen(
        settings = state.settings,
        statusLine = state.statusLine,
        llmConfigured = state.settings.llmConfigured,
        glasses = glasses,
        speaking = state.speak,
        onSave = { viewModel.saveSettings(it); pop() },
        onTestLlm = viewModel::testLlm,
        onTestSam = viewModel::testSam,
        onTestAsr = viewModel::testAsr,
        onSpeak = viewModel::speak,
        onStopVoice = viewModel::stopSpeaking,
        onOpenLive = onOpenLive,
        onOpenClassic = onOpenClassic,
        onBack = pop,
    )
}
