package dev.sam3vision.app

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.app.glasses.GlassesEvent
import dev.sam3vision.app.glasses.LocalBenchOpener
import dev.sam3vision.app.glasses.bench.BenchEntryPoint
import dev.sam3vision.app.glasses.bench.BenchScreen
import dev.sam3vision.app.ihear.IhearHome
import dev.sam3vision.app.ihear.IhearViewModel
import dev.sam3vision.app.ihear.OnboardingGesturesScreen
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
     * Page du proche qui suit la position partagée (rôle `Caregiver` des Réglages) :
     * la carte du site, tenue dans une WebView, avec la barre de retour.
     */
    data object Caregiver : Route

    /** Onboarding des trois gestes : ouvert depuis les Réglages, rejouable à volonté. */
    data object Onboarding : Route

    /**
     * Banc d'essai lunettes (session DAT réelle ou appareil simulé, journal, voix).
     * Ouvert depuis le bloc « Lunettes » des Réglages : c'est un écran d'iSee, plus
     * une `Activity` séparée — les paires simulées et la recette adb n'ont plus
     * d'entrée propre.
     */
    data object Bench : Route

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
 *
 * L'onboarding des trois gestes ([Route.Onboarding]) est un écran de la pile :
 * ouvert depuis les Réglages, rejouable sans réinstaller l'app.
 */
/**
 * Ouvre le banc quand une demande est posée de l'extérieur — la recette adb
 * (`--ez mock true`) arrive avant la composition de la pile. Les options sont
 * consommées par [BenchScreen] : ici on ne fait que pousser l'écran.
 */
@Composable
private fun BenchRequestEffect(push: (Route) -> Unit) {
    val request by BenchEntryPoint.pending.collectAsState()
    LaunchedEffect(request) { if (request != null) push(Route.Bench) }
}

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
    StartRouteEffect(state.settings, state.settingsReady) { stack = listOf(Route.Caregiver) }
    BenchRequestEffect(push)
    BackHandler(enabled = stack.size > 1) { pop() }

    Box(Modifier.fillMaxSize()) {
        // Les lunettes poussent leurs photos directement dans la navigation.
        GlassesBridge(glasses = glasses, viewModel = viewModel, push = push)
        RouteHost(
            route = route,
            state = state,
            viewModel = viewModel,
            ihear = ihear,
            glasses = glasses,
            liveViewModel = liveViewModel,
            push = push,
            pop = pop,
            // Blind person choisi : retour immédiat à iSee, comme Caregiver ouvre la page.
            onHome = { stack = listOf(Route.Ihear) },
            // Page du proche à la racine (rôle Caregiver au lancement) : « Retour » ouvre
            // les Réglages, sinon le bouton ne ferait rien du tout.
            onCaregiverBack = { if (stack.size > 1) pop() else push(Route.Settings) },
        )
        state.dictation.overlay?.let { overlay ->
            DictationOverlay(overlay = overlay, onDismiss = viewModel::dismissOverlay)
        }
    }
}

/**
 * Le rôle enregistré choisit la page d'accueil, une seule fois au lancement :
 * iSee pour la personne accompagnée (défaut), la carte du proche pour Caregiver.
 */
@Composable
private fun StartRouteEffect(settings: Settings, ready: Boolean, onCaregiverHome: () -> Unit) {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (started || !ready) return@LaunchedEffect
        started = true
        if (settings.caregiverRole == CaregiverRole.Caregiver) onCaregiverHome()
    }
}

/** L'écran de la route courante : une entrée par [Route]. */
@Composable
private fun RouteHost(
    route: Route,
    state: UiState,
    viewModel: AppViewModel,
    ihear: IhearViewModel,
    glasses: GlassesController,
    liveViewModel: LiveViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
    onHome: () -> Unit,
    onCaregiverBack: () -> Unit,
) {
    when (route) {
        Route.Ihear -> IhearHome(
            viewModel = ihear,
            onOpenSettings = { push(Route.Settings) },
        )
        Route.Capture -> CaptureRoute(viewModel, push)
        Route.Analysis -> AnalysisRoute(state, viewModel, push, pop)
        Route.Live -> LiveRoute(viewModel, liveViewModel, push, pop, state.settings)
        Route.Chat -> ChatRoute(state, viewModel, pop)
        is Route.Tap -> TapCaptureScreen(state.settings, pop, route.autoStart)
        Route.Caregiver -> CaregiverScreen(
            link = shareLink(state.settings.parentSiteUrl, state.settings.shareId),
            onBack = onCaregiverBack,
        )
        Route.Onboarding -> OnboardingGesturesScreen(onClose = pop)
        Route.Bench -> BenchScreen(onBack = pop)
        Route.Settings -> SettingsEntry(state, viewModel, glasses, push, pop, onHome)
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
    settings: Settings,
) {
    val context = LocalContext.current
    val liveState by liveViewModel.state.collectAsState()
    ShareRelay(liveState.distanceLayer, settings, context)
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

/**
 * Relais GPS+objets vers le site parent pendant le Direct : écoute active tant
 * que l'écran est affiché, envoi à chaque couche de distances (throttle 2 s).
 * [ShareGate] dit au service de suivi de patienter, pour ne pas doubler l'envoi.
 */
@Composable
private fun ShareRelay(layer: DistanceLayer?, settings: Settings, context: Context) {
    val broadcaster = remember(context) { ShareBroadcaster(context) }
    LaunchedEffect(settings.shareEnabled) {
        ShareGate.screenPublishing = settings.shareEnabled
        broadcaster.start(settings.shareEnabled)
    }
    DisposableEffect(Unit) {
        onDispose {
            ShareGate.screenPublishing = false
            broadcaster.stop()
        }
    }
    LaunchedEffect(layer) {
        if (layer != null && !layer.empty) {
            broadcaster.publish(System.currentTimeMillis(), settings, layer)
        }
    }
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

/**
 * Réglages : le banc des lunettes est fourni par un `CompositionLocal` (`remember` :
 * la valeur ne doit pas changer à chaque recomposition). Choisir le rôle Caregiver
 * enregistre le rôle **et** ouvre la carte du proche.
 */
@Composable
private fun SettingsEntry(
    state: UiState,
    viewModel: AppViewModel,
    glasses: GlassesController,
    push: (Route) -> Unit,
    pop: () -> Unit,
    onHome: () -> Unit,
) {
    CompositionLocalProvider(LocalBenchOpener provides remember { { push(Route.Bench) } }) {
        SettingsRoute(
            viewModel = viewModel,
            state = state,
            glasses = glasses,
            pop = pop,
            onOpenLive = { push(Route.Live) },
            onOpenClassic = { push(Route.Capture) },
            onOpenGestures = { push(Route.Onboarding) },
            onPickRole = { next ->
                viewModel.saveSettings(next)
                if (next.caregiverRole == CaregiverRole.Caregiver) push(Route.Caregiver) else onHome()
            },
        )
    }
}

/** Réglages : délègue au ViewModel et ouvre le banc des lunettes. */
@Composable
private fun SettingsRoute(
    viewModel: AppViewModel,
    state: UiState,
    glasses: GlassesController,
    pop: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenClassic: () -> Unit,
    onOpenGestures: () -> Unit,
    onPickRole: (Settings) -> Unit,
) {
    val context = LocalContext.current
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
        onOpenGestures = onOpenGestures,
        onPickRole = onPickRole,
        onStartTracking = { siteUrl, shareId ->
            (context as? MainActivity)?.ensureSharePermissions()
            ShareTrackingService.start(context)
            shareParentSite(context, siteUrl, shareId)
        },
        onStopTracking = {
            context.stopService(Intent(context, ShareTrackingService::class.java))
        },
        onBack = pop,
    )
}
