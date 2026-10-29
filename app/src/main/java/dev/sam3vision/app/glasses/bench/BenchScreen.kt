package dev.sam3vision.app.glasses.bench

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sam3vision.app.AppColors
import dev.sam3vision.app.BigButton
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.BuildDefaults
import dev.sam3vision.app.ScreenHeader
import dev.sam3vision.app.glasses.FrameOutcome
import dev.sam3vision.app.glasses.GlassesAnalysisStep
import dev.sam3vision.app.glasses.JpegFrame
import dev.sam3vision.app.llmClient
import dev.sam3vision.app.tap.findLifecycleOwner
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AnalyzeUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Écran « Banc d'essai lunettes » d'iSee : les lunettes (ou l'appareil simulé)
 * filment, SAM segmente, le LLM décrit, la voix annonce. Un journal, cinq gestes.
 *
 * Ce n'est plus une `Activity` : l'écran vit dans la pile de navigation d'iSee
 * ([dev.sam3vision.app.Route.Bench]) et le SDK Meta est déjà initialisé par
 * `GlassesController`. Le pipeline réseau vient de `:core`, le transport DAT de
 * [GlassesSession] / [GlassesFrameEncoder] — rien n'est réécrit ici.
 */
@Composable
fun BenchScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // La session DAT exige une `Activity` hôte : c'est celle d'iSee, retrouvée
    // derrière le `ContextThemeWrapper` de Compose.
    val activity = remember(context) { context.findLifecycleOwner() as? Activity }
    val bench = remember(context, scope, activity) { BenchController(context, scope, activity) }
    // Demande venue de l'extérieur (recette adb `--ez mock true`) : consommée une
    // seule fois, à la première composition de l'écran.
    val pending = remember { BenchEntryPoint.consume() }
    LaunchedEffect(bench, pending) { if (pending != null) bench.start(pending) }
    // Quitter l'écran coupe le cycle lunettes : aucune frame ne part ailleurs.
    DisposableEffect(bench) { onDispose { bench.close() } }
    BenchPermissionGate(bench)
    BenchContent(bench = bench, onBack = onBack)
}

/**
 * Demande les permissions du banc avant toute session, une seule fois à l'ouverture.
 * L'activité les demandait dans `onCreate` ; ici c'est l'écran qui arrive.
 */
@Composable
private fun BenchPermissionGate(bench: BenchController) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants -> bench.logPermissions(grants) }
    LaunchedEffect(bench) {
        val missing = bench.missingPermissions(context)
        if (missing.isNotEmpty()) launcher.launch(missing)
    }
}

/** Colonne de l'écran : en-tête, gestes, phrase entendue, journal. */
@Composable
private fun BenchContent(bench: BenchController, onBack: () -> Unit) {
    val lines by bench.lines.collectAsState()
    Column(
        Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(
            title = "Banc d'essai lunettes",
            subtitle = "SAM Vision — lunettes, voix seule",
            onBack = onBack,
            onSettings = null,
        )
        Column(
            Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            BenchActions(bench)
            if (bench.heard.isNotBlank()) {
                Text("« ${bench.heard} »", color = AppColors.success, fontSize = 15.sp)
            }
            BenchLog(lines)
        }
    }
}

/** Les cinq gestes du banc : démarrer, arrêter, armer l'analyse, diagnostiquer. */
@Composable
private fun BenchActions(bench: BenchController) {
    // En release, aucune paire simulée ne peut exister : le bouton disparaît.
    if (MockControl.available && !bench.running) {
        BigButton("Mock", true, AppColors.surfaceHigh, bench::startMock)
    }
    BigButton("Lunettes", !bench.running, AppColors.surfaceHigh, bench::startReal)
    BigButton("Stop", bench.running, AppColors.surfaceHigh, bench::stop)
    BigButton(
        label = if (bench.analyzing) "Couper l'analyse" else "Analyser",
        enabled = true,
        color = AppColors.primary,
        onClick = bench::toggleAnalyze,
    )
    BigButton("Diag", true, AppColors.surfaceHigh, bench::reportDiagnosis)
}

/** Journal borné ([SPIKE_LOG_LINES]) : l'écran montre la fin, pas le début. */
@Composable
private fun BenchLog(lines: List<String>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .background(AppColors.surface, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { line -> Text(line, color = AppColors.textMuted, fontSize = 11.sp) }
    }
}

/**
 * État et gestes du banc, hors composition : un seul cycle lunettes, un seul
 * journal, une seule voix. Sorti de l'`Activity` d'origine pour que l'écran reste
 * une simple vue — c'est la même logique, sans `runOnUiThread` ni `ViewModel`.
 */
internal class BenchController(
    context: Context,
    private val scope: CoroutineScope,
    private val activity: Activity?,
) {
    private val appContext = context.applicationContext

    /** Ce que cet APK embarque : une seule lecture de `BuildConfig` pour tout le banc. */
    private val build = BuildDefaults.FromBuild

    private val voice = GlassesVoice(appContext, build.ttsApiKey, build.ttsVoiceId)

    /** Journal : un flux, parce que [GlassesSession] journalise depuis plusieurs threads. */
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    var running by mutableStateOf(false)
        private set
    var heard by mutableStateOf("")
        private set
    var analyzing by mutableStateOf(false)
        private set

    private var lastFrame: JpegFrame? = null

    private val session = GlassesSession(appContext, scope, onFrame = ::onFrame, onLog = ::log)

    /** Maillon « frame → analyse → voix », déjà éprouvé sur JVM (`ChaineCompleteTest`). */
    private val analysisStep by lazy {
        GlassesAnalysisStep(analyze = { analyze.invoke(it) }, say = { voice.say(it) })
    }
    private val analyze: AnalyzeUseCase by lazy { buildPipeline() }

    // --- gestes ---------------------------------------------------------------

    /** Mode sans lunettes : appareil simulé + caméra du téléphone comme flux. */
    fun startMock() = applyLaunchPlan(SpikeLaunch.Options(mock = true))

    /** Entrée demandée de l'extérieur (extras adb) : le plan est celui de [SpikeLaunch]. */
    fun start(options: SpikeLaunch.Options) = applyLaunchPlan(options)

    /**
     * Mode réel : lunettes appairées à l'app Meta AI, Developer Mode activé.
     *
     * Les paires simulées d'un run précédent sont retirées **avant** la session :
     * `AutoDeviceSelector` prend le premier appareil qui convient, et une paire
     * fictive restée dans le SDK ferait passer la caméra du téléphone pour de
     * vraies lunettes.
     */
    fun startReal() {
        log("mode réel : recherche de vraies lunettes appairées")
        MockControl.dropSimulatedPairs(appContext, ::log)
        scope.launch {
            logRealStart(::log)
            launchSession()
        }
    }

    /** Diagnostic à la demande : même lecture que le démarrage réel, sans session. */
    fun reportDiagnosis() {
        log("diagnostic : état des lunettes vu par le SDK")
        scope.launch { logRealStart(::log) }
    }

    /** Bascule l'envoi réseau : par défaut le banc ne fait que prouver la réception. */
    fun toggleAnalyze() {
        analyzing = !analyzing
        log(if (analyzing) ANALYZE_ARMED_MESSAGE else "analyse réseau coupée")
        if (!analyzing) return
        val frame = lastFrame
        if (frame != null) scope.launch { onFrame(frame) } else log("aucune frame reçue pour l'instant")
    }

    fun stop() {
        log("arrêt demandé")
        session.stop()
        voice.stop()
        MockControl.dropSimulatedPairs(appContext, ::log)
        running = false
    }

    /** Quitter l'écran libère le cycle, la voix et la paire simulée éventuelle. */
    fun close() {
        session.stop()
        voice.stop()
        running = false
    }

    // --- démarrage ------------------------------------------------------------

    /** Exécute le plan de lancement dans l'ordre décidé par [SpikeLaunch.plan]. */
    private fun applyLaunchPlan(options: SpikeLaunch.Options) {
        SpikeLaunch.plan(options).forEach(::applyLaunchStep)
    }

    private fun applyLaunchStep(step: LaunchStep) {
        when (step) {
            LaunchStep.ARM_ANALYZE -> armAnalysis()
            LaunchStep.ENABLE_MOCK -> enableMock()
            // `MockControl.start` apparie ET alimente le flux caméra : les deux
            // étapes du plan tombent sur le même appel, une seule fois.
            LaunchStep.PHONE_CAMERA_FEED -> Unit
            LaunchStep.START_SESSION -> launchSession()
        }
    }

    /** Autorise l'envoi réseau avant toute session : la première frame part à SAM/LLM. */
    private fun armAnalysis() {
        analyzing = true
        log(ANALYZE_ARMED_MESSAGE)
    }

    /** Active le mock (apparié un appareil simulé), sans ouvrir de session. */
    private fun enableMock() {
        log("mode mock : caméra du téléphone en guise de lunettes")
        MockControl.start(appContext, ::log)
    }

    private fun launchSession() {
        val host = activity
        if (host == null) {
            log("session impossible : aucune Activity hôte")
            return
        }
        if (running) return
        running = true
        log(voice.routeToGlasses(knownDeviceName()))
        session.start(host)
    }

    // --- pipeline -------------------------------------------------------------

    /** Une frame → SAM/LLM → voix. Un seul appel réseau par frame, aucune exception qui remonte. */
    private suspend fun onFrame(frame: JpegFrame) {
        lastFrame = frame
        if (!analyzing) {
            log("frame ${frame.width}x${frame.height} — ${frame.base64.length / 1024} Ko base64")
            return
        }
        when (val sortie = analysisStep.onFrame(frame, ::log)) {
            is FrameOutcome.Dit -> heard = sortie.description
            is FrameOutcome.Muet -> Unit
        }
    }

    private fun buildPipeline(): AnalyzeUseCase {
        val settings = Settings(
            llmBaseUrl = build.llmBaseUrl,
            llmApiKey = build.llmApiKey,
            llmModel = build.llmModel,
            // Endpoint tap SAM 3.1 (seed `sam31.endpoint`), pas le SAM photo :
            // le banc segmente via l'app tap.
            samBaseUrl = BuildConfig.SAM31_ENDPOINT,
            ttsApiKey = build.ttsApiKey,
            ttsVoiceId = build.ttsVoiceId,
        )
        val sam = settings.samBaseUrl.takeIf { it.isNotBlank() }?.let(::HttpSamClient)
        if (!settings.llmConfigured) log("ATTENTION : llm.api.key absente de local.properties")
        // Client LLM unique de l'app ([llmClient]) : même langue que la voix, zéro doublon.
        return AnalyzeUseCase(llmClient(settings), sam)
    }

    // --- plateforme -----------------------------------------------------------

    /** Permissions encore manquantes pour une session DAT (à demander par l'écran). */
    fun missingPermissions(context: Context): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        add(Manifest.permission.CAMERA)
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        .toTypedArray()

    /** Le journal retient le verdict des permissions : c'est la cause n°1 d'échec. */
    fun logPermissions(grants: Map<String, Boolean>) {
        log("permissions : ${grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
    }

    /** Journal double : écran + logcat (indispensable pour un test automatisé). */
    private fun log(line: String) {
        Log.i(TAG, line)
        _lines.update { appendLogLine(it, line) }
    }

    private companion object {
        // TAG historique du banc : `tools/phone-lunettes.sh` et la doc le filtrent.
        const val TAG = "GlassesSpike"
    }
}