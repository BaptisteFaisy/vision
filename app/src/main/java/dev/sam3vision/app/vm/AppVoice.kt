package dev.sam3vision.app.vm

import dev.sam3vision.app.SPEAK_TIMEOUT
import dev.sam3vision.app.SPEAK_TIMEOUT_MS
import dev.sam3vision.app.SpeakState
import dev.sam3vision.app.speakRefusal
import dev.sam3vision.app.testVoiceText
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * La voix du chat et de l'analyse : synthèse ElevenLabs puis lecture, une phrase à la
 * fois. Le refus local ([speakRefusal] : texte vide, clé absente) part avant tout réseau ;
 * la synthèse est bornée dans le temps, donc l'état « en cours » ne reste jamais sans fin.
 *
 * [play] et [stopPlayback] sont les seules prises sur le lecteur (`SpeechPlayer`), ce qui
 * rend la classe testable sans `MediaPlayer`. La synthèse par défaut est celle de `:core`,
 * jamais réécrite ici.
 */
class AppVoice(
    private val settings: () -> Settings,
    private val update: StateUpdate,
    private val scope: CoroutineScope,
    private val play: suspend (ByteArray) -> Result<Unit>,
    private val stopPlayback: () -> Unit,
    private val timeoutMs: Long = SPEAK_TIMEOUT_MS,
    private val synthesize: suspend (Settings, String) -> Result<ByteArray> = { current, phrase ->
        ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId).synthesize(phrase)
    },
) {
    private var job: Job? = null

    /** Lit [text] à voix haute ; une seule phrase active à la fois. */
    fun speak(text: String) {
        val current = settings()
        val refusal = speakRefusal(text, current.ttsConfigured)
        job?.cancel()
        stopPlayback()
        if (refusal != null) {
            fail(text.trim(), refusal)
            return
        }
        job = scope.launch { synthesizeAndPlay(current, text.trim()) }
    }

    /** Bouton « Tester la voix » des réglages. */
    fun testVoice() = speak(testVoiceText(settings().speechLanguage))

    /** Coupe la phrase en cours et revient à l'état inactif. */
    fun stop() {
        job?.cancel()
        job = null
        stopPlayback()
        update { it.copy(speak = SpeakState.Idle) }
    }

    /** Fin de vie du ViewModel : plus aucune synthèse en vol. */
    fun cancel() {
        job?.cancel()
    }

    /** MediaPlayer signale la fin de lecture depuis son propre thread : StateFlow est sûr. */
    fun onFinished() = update { st ->
        val current = st.speak
        if (current is SpeakState.Playing) st.copy(speak = SpeakState.Done(current.text)) else st
    }

    /** Erreur de lecture signalée par le lecteur lui-même. */
    fun onError(message: String) = update { st ->
        val current = st.speak
        if (current is SpeakState.Playing) st.copy(speak = SpeakState.Error(current.text, message)) else st
    }

    /** Synthèse bornée dans le temps : jamais d'état « en cours » sans fin. */
    private suspend fun synthesizeAndPlay(current: Settings, phrase: String) {
        update { it.copy(speak = SpeakState.Synthesizing(phrase)) }
        val audio = withTimeoutOrNull(timeoutMs) { synthesize(current, phrase) }
        if (audio == null) {
            fail(phrase, SPEAK_TIMEOUT)
            return
        }
        audio.fold(
            onSuccess = { bytes -> playBytes(bytes, phrase) },
            onFailure = { error -> fail(phrase, error.message ?: AppStatusText.VOICE_UNAVAILABLE) },
        )
    }

    private suspend fun playBytes(bytes: ByteArray, phrase: String) {
        update { it.copy(speak = SpeakState.Playing(phrase)) }
        play(bytes).onFailure { error -> fail(phrase, AppStatusText.playbackFailed(error)) }
    }

    private fun fail(phrase: String, message: String) = update {
        it.copy(speak = SpeakState.Error(phrase, message))
    }
}