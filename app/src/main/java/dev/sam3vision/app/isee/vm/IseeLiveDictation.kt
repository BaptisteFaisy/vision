package dev.sam3vision.app.isee.vm

import dev.sam3vision.core.audio.PcmPreview
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.DictationUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Meta/Scribe rendent un texte par requête, sans résultats intermédiaires : une fenêtre de
 * six secondes est donc transcrite périodiquement. Une seule requête d'aperçu à la fois ;
 * sa réponse ne remplace jamais la dictée finale et ne survit ni au silence ni au relâchement.
 */
class IseeLiveDictation(
    private val scope: CoroutineScope,
    private val snapshot: () -> PcmPreview,
    private val engine: (Settings) -> SpeechClient?,
    private val update: StateUpdate,
    private val intervalMs: Long = 1_500L,
    private val pollMs: Long = 80L,
) {
    private var meter: Job? = null
    private var captions: Job? = null
    private var latest: PcmPreview? = null
    private var turn = 0L

    fun start(settings: Settings) {
        stop()
        meter = scope.launch { watchVoice() }
        val client = engine(settings) ?: return
        captions = scope.launch { transcribe(client) }
    }

    fun stop() {
        meter?.cancel()
        captions?.cancel()
        meter = null
        captions = null
        latest = null
        turn++
        update { it.copy(userSpeaking = false, liveTranscript = "") }
    }

    private suspend fun watchVoice() {
        var speaking = false
        while (currentCoroutineContext().isActive) {
            val sample = snapshot()
            latest = sample
            if (sample.speaking != speaking) {
                speaking = sample.speaking
                turn++
                update { it.copy(userSpeaking = speaking, liveTranscript = "") }
            }
            delay(pollMs)
        }
    }

    private suspend fun transcribe(client: SpeechClient) {
        var sentBytes = 0L
        while (currentCoroutineContext().isActive) {
            delay(intervalMs)
            val sample = latest?.takeIf { it.speaking && it.totalBytes > sentBytes } ?: continue
            val currentTurn = turn
            sentBytes = sample.totalBytes
            val result = withTimeoutOrNull(4_000L) { DictationUseCase(client).invoke(sample.pcm) }
            currentCoroutineContext().ensureActive()
            if (currentTurn == turn && latest?.speaking == true) {
                result?.getOrNull()?.let { text ->
                    update { if (it.holding) it.copy(liveTranscript = text) else it }
                }
            }
            if (result?.isSuccess != true) delay(3_000L)
        }
    }
}
