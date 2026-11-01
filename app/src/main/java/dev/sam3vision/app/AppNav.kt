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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.sam3vision.app.glasses.GlassesController
import dev.sam3vision.core.settings.CaregiverRole
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.share.sharedPictosOf
import dev.sam3vision.app.glasses.LocalBenchOpener
import dev.sam3vision.app.glasses.bench.BenchEntryPoint
import dev.sam3vision.app.glasses.bench.BenchScreen
import dev.sam3vision.app.isee.IseeHome
import dev.sam3vision.app.isee.IseeViewModel
import dev.sam3vision.app.isee.OnboardingGesturesScreen
import dev.sam3vision.app.voicenav.VoiceNavRoute
import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.app.tap.TapCaptureScreen

/** Extra adb `--es route <nom>` : ouvre l'écran nommé (recettes, comme le banc). */
const val EXTRA_ROUTE = "route"

/**
 * Extra adb `--es destination <texte>` (debug seul) : démarre un guidage vers
 * la destination, comme une dictée « aller à … » confirmée (recette carte).
 */
const val EXTRA_DESTINATION = "destination"

/**
 * Extra adb `--es voice <texte>` (debug seul) : injecte un transcript comme si
 * le direct l'avait entendu — mêmes parseurs que les ordres voix du direct
 * (navigation, traversée), sans passer par la STT (recette voix sans voix).
 */
const val EXTRA_VOICE = "voice"

/**
 * Demande d'écran posée de l'extérieur : `MainActivity` y dépose le nom,
 * `AppNav` la consomme et pousse la route (inconnue = accueil, jamais vide).
 */
object RouteRequest {
    private val _pending = MutableStateFlow<String?>(null)
    val pending: StateFlow<String?> = _pending
    fun push(name: String) {
        _pending.value = name
    }
    fun consume(): String? = _pending.value.also { _pending.value = null }
}

/**
 * Ouvre l'écran demandé de l'extérieur — la recette adb (`--es route ...`)
 * arrive avant la composition de la pile. La demande est consommée ici : un
 * seul push, même si l'effet rejoue sur la remise à `null`.
 */
@Composable
private fun RouteRequestEffect(push: (Route) -> Unit) {
    val request by RouteRequest.pending.collectAsState()
    LaunchedEffect(request) {
        RouteRequest.consume()?.let { push(routeFromName(it)) }
    }
}

/** Routes de l'app (navigation par état, sans dépendance navigation-compose). */
sealed interface Route {
    /** Page d'accueil iSee : chat plein écran + fenêtre caméra flottante. */
    data object Isee : Route
    data object Capture : Route
    data object Analysis : Route
    data object Chat : Route
    data object Live : Route
    data object Settings : Route

    /**
     * Loved one page following the shared position (`Caregiver` role from
     * Settings): the Portrait embedded in the WebView, fed by the received link.
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

    /**
     * Navigation vocale in-app (URL Valhalla configurée) : arrivée seule,
     * poussée par l'état de session — jamais ouverte à la main. La
     * confirmation et le guidage vivent sur l'accueil iSee (caméra + carte dessous).
     */
    data object VoiceNav : Route
}

/**
 * Racine de navigation : iSee en page d'accueil (chat + fenêtre caméra),
 * puis les écrans d'origine. Les gestes racine (IseeRootLayout) pilotent la
 * caméra et le chat via [IseeViewModel] quand iSee est à l'écran.
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


/**
 * Destination dictée sur le direct : la session de navigation de l'accueil la
 * reçoit **telle quelle** (mêmes parseurs qu'une dictée de l'accueil) ;
 * l'écran navigation est poussé par [VoiceNavStateEffect] dès que la session
 * quitte le repos. Sans cette passerelle, « aller gare Montparnasse » dit sur
 * le direct répondait par une description de la pièce.
 */
@Composable
private fun NavOrderEffect(isee: IseeViewModel) {
    val request by NavOrderRequest.pending.collectAsState()
    LaunchedEffect(request) {
        val transcript = NavOrderRequest.consume() ?: return@LaunchedEffect
        isee.acceptNavOrder(transcript)
    }
}

/**
 * L'état de session pilote l'écran navigation : arrivée = poussé (une fois),
 * tout le reste = dépilé (arrivée fermée, arrêt, annulation). La confirmation
 * et le guidage vivent sur l'accueil iSee (caméra + carte dessous,
 * questions et tap caméra inchangés) : s'ils sont poussés, ils sont dépilés.
 * Le retour système dépile sans repousse : quitter l'arrivée la referme.
 */
@Composable
private fun VoiceNavStateEffect(
    isee: IseeViewModel,
    route: Route,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    val navState by isee.voiceNavState.collectAsState()
    LaunchedEffect(navState) {
        when {
            navState is VoiceNavUiState.Arrived -> if (route != Route.VoiceNav) push(Route.VoiceNav)
            route == Route.VoiceNav -> pop()
            else -> Unit
        }
    }
}

@Composable
fun AppNav(
    viewModel: AppViewModel,
    isee: IseeViewModel,
    glasses: GlassesController,
    liveViewModel: LiveViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Pile sauvegardée sous forme de noms : une rotation ne renvoie plus à l'accueil.
    // `stack` est dérivée à chaque composition ; toute écriture passe par `names`.
    var names by rememberSaveable { mutableStateOf(listOf("isee")) }
    val stack: List<Route> = names.map(::routeFromName).ifEmpty { listOf(Route.Isee) }
    val route = stack.first()
    val push: (Route) -> Unit = { names = listOf(routeToName(it)) + names }
    val pop: () -> Unit = { if (names.size > 1) names = names.drop(1) }
    val home: () -> Unit = { names = listOf(routeToName(Route.Isee)) }
    StartRouteEffect(state.settings, state.settingsReady) { names = listOf(routeToName(Route.Caregiver)) }
    BenchRequestEffect(push)
    RouteRequestEffect(push)
    NavOrderEffect(isee)
    VoiceNavStateEffect(isee, route, push, pop)
    BackHandler(enabled = stack.size > 1) { pop() }
    // Accueil iSee plein écran : le téléphone ne doit pas se mettre en veille.
    KeepScreenOnEffect(keepsScreenOn(route))

    Box(Modifier.fillMaxSize()) {
        // Les lunettes nourrissent iSee : photos posées sans changer de page, questions parlées comme des dictées.
        GlassesBridge(glasses = glasses, isee = isee, push = push)
        RouteHost(
            route = route,
            state = state,
            viewModel = viewModel,
            isee = isee,
            glasses = glasses,
            liveViewModel = liveViewModel,
            push = push,
            pop = pop,
            // Blind person picked: straight back to iSee, as Caregiver opens the page.
            onHome = home,
            // À la racine, la roue dentée et le retour système ouvrent les Réglages.
            onCaregiverBack = { if (stack.size > 1) pop() else push(Route.Settings) },
        )
        state.dictation.overlay?.let { overlay ->
            DictationOverlay(overlay = overlay, onDismiss = viewModel::dismissOverlay)
        }
    }
}

/**
 * The saved role picks the home page, once at launch: iSee for the
 * accompanied person (default), the loved one's map for Caregiver.
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
    isee: IseeViewModel,
    glasses: GlassesController,
    liveViewModel: LiveViewModel,
    push: (Route) -> Unit,
    pop: () -> Unit,
    onHome: () -> Unit,
    onCaregiverBack: () -> Unit,
) {
    when (route) {
        Route.Isee -> IseeHome(
            viewModel = isee,
            onOpenSettings = { push(Route.Settings) },
            settings = state.settings,
            sharingReady = state.settingsReady,
        )
        Route.Capture -> CaptureRoute(viewModel, push)
        Route.Analysis -> AnalysisRoute(state, viewModel, push, pop)
        Route.Live -> LiveRoute(viewModel, liveViewModel, push, pop, state.settings)
        Route.Chat -> ChatRoute(state, viewModel, pop)
        is Route.Tap -> TapCaptureScreen(state.settings, pop, route.autoStart)
        Route.Caregiver -> CaregiverScreen(
            link = caregiverShareLink(state.settings),
            onBack = onCaregiverBack,
        )
        Route.Onboarding -> OnboardingGesturesScreen(onClose = pop)
        Route.Bench -> BenchScreen(onBack = pop)
        Route.VoiceNav -> VoiceNavRoute(isee, state.settings)
        Route.Settings -> SettingsEntry(state, viewModel, glasses, push, pop, onHome)
    }
}

/** Nom stable d'une route, pour la sauvegarde d'état (rotation, process death). */
internal fun routeToName(route: Route): String = when (route) {
    Route.Isee -> "isee"
    Route.Capture -> "capture"
    Route.Analysis -> "analysis"
    Route.Chat -> "chat"
    Route.Live -> "live"
    Route.Settings -> "settings"
    Route.Caregiver -> "caregiver"
    Route.Onboarding -> "onboarding"
    Route.Bench -> "bench"
    Route.VoiceNav -> "voicenav"
    is Route.Tap -> "tap:${route.autoStart}"
}

/** Route depuis son nom ; inconnue = accueil (jamais d'écran vide). */
internal fun routeFromName(name: String): Route = when {
    name == "isee" -> Route.Isee
    name == "capture" -> Route.Capture
    name == "analysis" -> Route.Analysis
    name == "chat" -> Route.Chat
    name == "live" -> Route.Live
    name == "settings" -> Route.Settings
    name == "caregiver" -> Route.Caregiver
    name == "onboarding" -> Route.Onboarding
    name == "bench" -> Route.Bench
    name == "voicenav" -> Route.VoiceNav
    name.startsWith("tap:") -> Route.Tap(name.substringAfter(':').toBoolean())
    else -> Route.Isee
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
        onOpenHome = { HomePresetRequest.pushDefault(); push(Route.Live) },
        onShown = viewModel::prewarmGpu,
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
    val liveState by liveViewModel.state.collectAsState()
    ShareRelay(
        liveState.distanceLayer,
        settings,
        pictos = { now -> sharedPictosOf(liveState.pictoOverlay?.hits.orEmpty(), liveViewModel.asker::pngOf, now) },
    )
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

/**
 * Réglages : le banc des lunettes est fourni par un `CompositionLocal` (`remember` :
 * la valeur ne doit pas changer à chaque recomposition). Picking the Caregiver
 * role saves the role **and** opens the loved one's map.
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

/**
 * Veille GPU de la page Réglages : le téléphone remarque tout seul quand on branche
 * la carte ; en partant, la veille s'annule (jamais de sonde hors de cette page).
 */
@Composable
private fun GpuWatchEffect(viewModel: AppViewModel) {
    DisposableEffect(Unit) {
        viewModel.setGpuWatching(true)
        onDispose { viewModel.setGpuWatching(false) }
    }
}

/**
 * Enregistre le brouillon avant de partir : le service relit les réglages
 * sauvegardés, et un lien ou flux modifié sans « Enregistrer » partait
 * dans le vide (400/403 silencieux).
 */
private fun startParentTracking(viewModel: AppViewModel, context: Context, draft: Settings) {
    viewModel.saveSettings(draft)
    (context as? MainActivity)?.ensureSharePermissions()
    ShareTrackingService.start(context)
    shareParentSite(context, draft.parentSiteUrl, draft.shareId)
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
    val gpu by viewModel.gpu.collectAsState()
    GpuWatchEffect(viewModel)
    SettingsScreen(
        settings = state.settings,
        statusLine = state.statusLine,
        llmConfigured = state.settings.llmConfigured,
        glasses = glasses,
        speaking = state.speak,
        gpu = gpu,
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
        onStartTracking = { draft -> startParentTracking(viewModel, context, draft) },
        onStopTracking = {
            context.stopService(Intent(context, ShareTrackingService::class.java))
        },
        onBack = pop,
    )
}
