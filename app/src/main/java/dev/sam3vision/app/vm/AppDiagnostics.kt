package dev.sam3vision.app.vm

import android.util.Log
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.UiState
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.samEndpoint
import dev.sam3vision.app.testBitmap
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.net.HttpSamClient
import dev.sam3vision.core.net.LlmClient
import dev.sam3vision.core.net.SamClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.net.releaseSam
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.settings.resolveSamEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Les trois boutons « Tester … » des Réglages, et l'extinction de la GPU quand l'app
 * se ferme.
 *
 * Un réglage manquant est refusé **localement** : message clair dans `statusLine`, et
 * aucun appel réseau (c'est vérifié par les tests). Un réglage présent déclenche le
 * vrai client de `:core` — jamais une réimplémentation ici.
 */
class AppDiagnostics(
    private val state: () -> UiState,
    private val update: StateUpdate,
    private val llm: (Settings) -> LlmClient,
    private val speech: (Settings) -> SpeechClient?,
    private val probeImage: () -> String = { bitmapToJpegBase64(testBitmap()) },
    private val samUrl: (Settings) -> String = { it.samEndpoint() },
    private val samClient: (String) -> SamClient = { HttpSamClient(it) },
    private val releaseScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** « Tester le LLM » : une mini image verte, pour ne pas consommer de crédit. */
    suspend fun testLlm() {
        val settings = state().settings
        update { it.copy(statusLine = AppStatusText.LLM_TESTING) }
        val outcome = llm(settings).describe(probeImage(), listOf(PROBE_LABEL))
        update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { AppStatusText.llmOk(it.description) },
                onFailure = { AppStatusText.llmFailed(it) },
            ))
        }
    }

    /** « Tester SAM » : sans endpoint, message clair et **aucun** appel réseau. */
    suspend fun testSam() {
        val settings = state().settings
        val url = samUrl(settings)
        if (url.isBlank()) {
            update { it.copy(statusLine = AppStatusText.SAM_NO_URL) }
            return
        }
        update { it.copy(statusLine = AppStatusText.samTesting(settings.samRegion.code)) }
        val outcome = samClient(url).health()
        update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { ok -> if (ok) AppStatusText.SAM_OK else AppStatusText.SAM_HEALTH_FALSE },
                onFailure = { AppStatusText.samFailed(it) },
            ))
        }
    }

    /** « Tester la dictée » : 1 s de silence, seule la connexion est testée. */
    suspend fun testAsr() {
        val client = speech(state().settings)
        if (client == null) {
            update { it.copy(statusLine = AppStatusText.ASR_NO_ENGINE) }
            return
        }
        update { it.copy(statusLine = AppStatusText.ASR_TESTING) }
        val silence = ByteArray(Wav.SAMPLE_RATE * Wav.BYTES_PER_SAMPLE)
        val outcome = client.transcribe(Wav.pcm16(silence))
        update {
            it.copy(statusLine = outcome.fold(
                onSuccess = { AppStatusText.asrOk(it) },
                onFailure = { AppStatusText.asrFailed(it) },
            ))
        }
    }

    /**
     * Réveil de la GPU à l'ouverture de l'app (`GET /health`, silencieux).
     *
     * Sur un conteneur endormi, `/health` le réveille (snapshot Modal) ; sur un
     * conteneur chaud, c'est une requête quasi gratuite. Best-effort : le verdict est
     * ignoré, la sonde iSee et le Direct réveilleraient de toute façon la carte à leur
     * première frame. Aucune écriture dans `statusLine`, aucun `Log` — l'ouverture ne
     * doit ni clignoter ni dépendre d'Android (tests JVM).
     */
    suspend fun warmGpu() {
        val url = samUrl(state().settings)
        if (url.isBlank()) return
        samClient(url).health()
    }

    /**
     * Extinction volontaire du conteneur GPU à la fermeture de l'app.
     *
     * Hors du scope du ViewModel **exprès** : à `onDestroy`, le scope du ViewModel est
     * annulé, l'appel serait tué avant de partir. Le scope dédié survit le temps de la
     * requête.
     */
    fun releaseGpu() {
        val endpoint = state().settings
            .resolveSamEndpoint(BuildConfig.SAM_BASE_URL, BuildConfig.SAM_US_BASE_URL)
        if (endpoint.isBlank()) return
        releaseScope.launch {
            val outcome = releaseSam(endpoint)
            Log.i(
                "sam3vision",
                "extinction GPU (fermeture) -> $endpoint : " +
                    (outcome.getOrNull()?.let { "code=${it.code} ok=${it.released} corps=${it.body}" }
                        ?: "échec ${outcome.exceptionOrNull()?.message}"),
            )
        }
    }

    private companion object {
        /** Étiquette de la mini image de test LLM. */
        const val PROBE_LABEL = "vert"
    }
}