package dev.sam3vision.app.vm

import android.util.Log
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.app.UiState
import dev.sam3vision.app.bitmapToJpegBase64
import dev.sam3vision.app.samEndpoint
import dev.sam3vision.app.testBitmap
import dev.sam3vision.core.audio.Wav
import dev.sam3vision.core.net.ApiError
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
import kotlinx.coroutines.delay
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

    /**
     * Génération du réveil : chaque `warmGpu` et chaque `releaseGpu` l'incrémente — un
     * réveil dont la génération est dépassée s'arrête avant l'essai suivant. Passer en
     * arrière-plan en pleine série d'essais n'éteint donc pas pour rien : la carte ne
     * se rallume pas derrière l'extinction. (Confiné au thread de l'appelant : Main en
     * prod via `viewModelScope` et les rappels de cycle de vie, test seul en JVM.)
     */
    private var warmGeneration = 0

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
     * Activation de la GPU à l'ouverture de l'app (`GET /health`, silencieux).
     *
     * Sur un conteneur endormi, `/health` le réveille (snapshot Modal) ; sur un
     * conteneur chaud, c'est une requête quasi gratuite. Jusqu'à [WARM_ATTEMPTS]
     * essais espacés de [WARM_RETRY_DELAY_MS] : une ouverture dans un trou de réseau
     * ne doit pas laisser la carte éteinte. Arrêt immédiat sur
     * [ApiError.BackendStopped] — l'app Modal n'est plus déployée, réessayer est
     * inutile, seul un redéploiement (`deploy-live-fast.sh`) la rallume — et dès que
     * [releaseGpu] (ou un nouveau réveil) périme la génération : la carte ne se
     * rallume pas derrière l'extinction. Aucune écriture dans `statusLine`, aucun
     * `Log` — l'ouverture ne doit ni clignoter ni dépendre d'Android (tests JVM).
     */
    suspend fun warmGpu() {
        val url = samUrl(state().settings)
        if (url.isBlank()) return
        val client = samClient(url)
        val generation = ++warmGeneration
        repeat(WARM_ATTEMPTS) { attempt ->
            if (generation != warmGeneration) return
            val outcome = client.health()
            if (outcome.getOrNull() == true) return
            if (outcome.exceptionOrNull() is ApiError.BackendStopped) return
            if (attempt < WARM_ATTEMPTS - 1) delay(WARM_RETRY_DELAY_MS)
        }
    }

    /**
     * Extinction volontaire du conteneur GPU à la fermeture de l'app.
     *
     * Hors du scope du ViewModel **exprès** : à `onDestroy`, le scope du ViewModel est
     * annulé, l'appel serait tué avant de partir. Le scope dédié survit le temps de la
     * requête.
     */
    fun releaseGpu() {
        // Un réveil en cours est périmé dès qu'on éteint : sa génération dépassée
        // l'arrête avant l'essai suivant (pas de rallumage derrière l'extinction).
        warmGeneration++
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

        /** Essais de réveil à l'ouverture : assez pour un trou de réseau, borné. */
        const val WARM_ATTEMPTS = 3

        /** Pause entre deux essais de réveil (annulable si l'app repart en fond). */
        const val WARM_RETRY_DELAY_MS = 2_000L
    }
}