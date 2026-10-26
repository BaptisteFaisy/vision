package dev.sam3vision.glasses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.core.Wearables
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.net.InferHubLlmClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.AnalyzeUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spike « voix seule » : les lunettes (ou le mock) filment, SAM segmente, le LLM
 * décrit, la voix annonce. Un seul écran, un journal, cinq boutons — dont « Mock »
 * seulement en debug, et « Diag » qui rejoue le diagnostic sans ouvrir de session.
 *
 * Le pipeline réseau vient de `:core` (aucun appel HTTP réécrit ici) et le
 * transport DAT de `GlassesSession` / `GlassesFrameEncoder`.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var voice: GlassesVoice
    private var session: GlassesSession? = null

    private var lines by mutableStateOf<List<String>>(emptyList())
    private var running by mutableStateOf(false)
    private var heard by mutableStateOf("")
    private var analyzeFrames by mutableStateOf(false)
    private var lastFrame: JpegFrame? = null

    private val analyze: AnalyzeUseCase by lazy { buildPipeline() }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            log("permissions : ${grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" }}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = GlassesVoice(this, BuildConfig.TTS_API_KEY, BuildConfig.TTS_VOICE_ID)
        askPermissions()
        // Retour d'enregistrement Meta AI (scheme `samglasses` du manifest).
        handleRegistrationIntent(intent)
        session = GlassesSession(this, scope, onFrame = ::onFrame, onLog = ::log)

        // Portes de la recette pilotée par adb : `--ez mock true`, `--ez real true`.
        // Aussi dans onNewIntent : un `am start` sur une instance déjà vivante
        // n'appelle pas onCreate, et la recette croirait à un échec du mode réel.
        handleAutoStart(intent)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(
                        lines = lines,
                        running = running,
                        heard = heard,
                        onMock = ::startWithMock,
                        onReal = ::startWithGlasses,
                        onStop = ::stopAll,
                        onAnalyze = ::analyzeLastFrame,
                        onDiagnose = ::reportDiagnosis,
                        analyzing = analyzeFrames,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRegistrationIntent(intent)
        handleAutoStart(intent)
    }

    /** Démarre le mode demandé par extra ; sans extra, l'écran attend un doigt. */
    private fun handleAutoStart(intent: Intent?) {
        when {
            intent?.getBooleanExtra(EXTRA_AUTO_MOCK, false) == true ->
                window.decorView.post { startWithMock() }

            intent?.getBooleanExtra(EXTRA_AUTO_REAL, false) == true ->
                window.decorView.post { startWithGlasses() }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        session?.stop()
        voice.stop()
        super.onDestroy()
    }

    // --- démarrage -----------------------------------------------------------

    /** Mode sans lunettes : appareil simulé + caméra du téléphone comme flux. */
    private fun startWithMock() {
        log("mode mock : caméra du téléphone en guise de lunettes")
        MockControl.start(this, ::log)
        launchSession()
    }

    /**
     * Mode réel : lunettes appairées à l'app Meta AI, Developer Mode activé.
     *
     * Les paires simulées d'un run précédent sont retirées **avant** la session :
     * `AutoDeviceSelector` prend le premier appareil qui convient, et une paire
     * fictive restée dans le SDK ferait passer la caméra du téléphone pour de
     * vraies lunettes. Le diagnostic suit, puis la session s'ouvre même si tout
     * n'est pas prêt — c'est le SDK qui tranche, l'écran dit seulement pourquoi.
     */
    private fun startWithGlasses() {
        log("mode réel : recherche de vraies lunettes appairées")
        MockControl.dropSimulatedPairs(this, ::log)
        scope.launch {
            logRealStart(::log)
            launchSession()
        }
    }

    /** Diagnostic à la demande : même lecture que le démarrage réel, sans session. */
    private fun reportDiagnosis() {
        log("diagnostic : état des lunettes vu par le SDK")
        scope.launch { logRealStart(::log) }
    }

    private fun launchSession() {
        if (running) return
        running = true
        log(voice.routeToGlasses(knownDeviceName()))
        session?.start(this)
    }

    /** Bascule l'envoi réseau : par défaut le spike ne fait que prouver la réception. */
    private fun analyzeLastFrame() {
        analyzeFrames = !analyzeFrames
        log(if (analyzeFrames) "analyse réseau ACTIVÉE (1 frame / 1,5 s)" else "analyse réseau coupée")
        if (analyzeFrames) {
            val frame = lastFrame
            if (frame != null) scope.launch { onFrame(frame) } else log("aucune frame reçue pour l'instant")
        }
    }

    private fun stopAll() {
        log("arrêt demandé")
        session?.stop()
        voice.stop()
        MockControl.dropSimulatedPairs(this, ::log)
        running = false
    }

    // --- pipeline ------------------------------------------------------------

    /** Une frame → SAM/LLM → voix. Un seul appel réseau par frame, aucune exception qui remonte. */
    private suspend fun onFrame(frame: JpegFrame) {
        lastFrame = frame
        val ko = frame.base64.length
        log("frame ${frame.width}x${frame.height} — ${ko / 1024} Ko base64")
        if (!analyzeFrames) return
        val outcome = withContext(Dispatchers.IO) { analyze.invoke(frame.base64) }
        val analysis = outcome.getOrNull()
        if (analysis == null || analysis.description.isBlank()) {
            val why = outcome.exceptionOrNull()?.message ?: "description vide"
            log("analyse échouée : $why")
            return
        }
        log("objets : ${analysis.objects.map { it.label }.distinct().take(6)}")
        heard = analysis.description
        voice.say(analysis.description)?.let { log("voix : $it") }
    }

    private fun buildPipeline(): AnalyzeUseCase {
        val settings = Settings(
            llmBaseUrl = BuildConfig.LLM_BASE_URL,
            llmApiKey = BuildConfig.LLM_API_KEY,
            llmModel = BuildConfig.LLM_MODEL,
            samBaseUrl = BuildConfig.SAM31_ENDPOINT,
            ttsApiKey = BuildConfig.TTS_API_KEY,
            ttsVoiceId = BuildConfig.TTS_VOICE_ID,
        )
        val llm = InferHubLlmClient(settings.llmBaseUrl, settings.llmApiKey, settings.llmModel)
        val sam = if (settings.segmentationEnabled) HttpSamClient(settings.samBaseUrl) else null
        if (!settings.llmConfigured) log("ATTENTION : llm.api.key absente de local.properties")
        return AnalyzeUseCase(llm, sam)
    }

    // --- plateforme ----------------------------------------------------------

    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            add(Manifest.permission.CAMERA)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }

    private fun handleRegistrationIntent(intent: Intent?) {
        if (intent == null) return
        Wearables.handleIntent(intent) { request -> log("callback d'enregistrement reçu") }
            .onFailure { error, _ -> log("intent ignoré : $error") }
    }

    /** Journal double : écran + logcat (indispensable pour un test automatisé). */
    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { lines = appendLogLine(lines, line) }
    }

    private companion object {
        const val TAG = "GlassesSpike"
        const val EXTRA_AUTO_MOCK = "mock"
        const val EXTRA_AUTO_REAL = "real"
    }
}

@Composable
private fun SpikeScreen(
    lines: List<String>,
    running: Boolean,
    heard: String,
    onMock: () -> Unit,
    onReal: () -> Unit,
    onStop: () -> Unit,
    onAnalyze: () -> Unit,
    onDiagnose: () -> Unit,
    analyzing: Boolean,
) {
    Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("SAM Vision — lunettes, voix seule", style = MaterialTheme.typography.titleMedium)
        Row(modifier = Modifier.padding(vertical = 8.dp)) {
            if (MockControl.available) Button(onClick = onMock, enabled = !running) { Text("Mock") }
            Button(onClick = onReal, enabled = !running) { Text("Lunettes") }
            Button(onClick = onStop, enabled = running) { Text("Stop") }
            Button(onClick = onAnalyze) { Text(if (analyzing) "Couper" else "Analyser") }
            Button(onClick = onDiagnose) { Text("Diag") }
        }
        if (heard.isNotBlank()) {
            Text("« $heard »", style = MaterialTheme.typography.bodyLarge)
        }
        lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}