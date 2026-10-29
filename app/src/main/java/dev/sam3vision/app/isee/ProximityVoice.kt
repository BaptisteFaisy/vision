package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Voix de proximité de la fenêtre caméra : dit **une fois** chaque nouvelle phrase de
 * [LiveProbe] (« Personne à 1 mètre. »).
 *
 * Quatre garde-fous : la même phrase n'est jamais répétée, rien n'est dit sans clé
 * ElevenLabs, rien par-dessus la réponse du chat (une seule source sonore à la
 * fois — c'est l'appelant qui dit si le lecteur est libre), et une synthèse
 * annulée (phrase plus fraîche arrivée entre-temps, `collectLatest` côté sonde)
 * n'est jamais jouée.
 *
 * [urgent] = danger : coupe la lecture en cours et parle quand même, y compris
 * pendant une dictée — la sécurité passe avant le sous-titre (miroir de
 * `TraverseVoice`, qui préempte la verte par la rouge). Le « Stop. » urgent sort
 * d'un clip pré-synthétisé ([prewarmStop]) : zéro réseau au moment où chaque
 * dixième de seconde compte.
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
) {

    private var lastPhrase: String? = null

    /** Clip « Stop. » en mémoire, `null` tant que [prewarmStop] n'a pas abouti. */
    @Volatile
    private var stopClip: ByteArray? = null

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
    }

    /**
     * Pré-synthétise le « Stop. » urgent (une fois, à l'ouverture) : l'alerte
     * qui suit n'attend plus le réseau. Silencieux sans clé ou en échec — le
     * « Stop. » sera alors synthétisé à la volée, une fois, puis gardé.
     */
    suspend fun prewarmStop() {
        if (stopClip != null) return
        val current = settings()
        if (!current.ttsConfigured) return
        stopClip = ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(STOP_WORD).getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon on ne dit rien (et la phrase reste mémorisée comme déjà traitée, pour ne pas
     * la dire en retard une fois le chat terminé). [urgent] coupe la lecture en cours
     * et parle quand même, dictée ou pas.
     */
    suspend fun onPhrase(phrase: String, playerIdle: Boolean, urgent: Boolean = false) {
        if (phrase == lastPhrase) return
        lastPhrase = phrase
        val current = settings()
        if (!current.ttsConfigured) return
        if (!urgent && !playerIdle) return
        if (urgent) player.stop()
        if (urgent && phrase == STOP_WORD) {
            playStop(current)
            return
        }
        val bytes = ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(phrase)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && (urgent || canPlay())) {
            onSpeaking(phrase)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /** « Stop. » urgent : clip pré-synthétisé si prêt, sinon synthèse gardée pour la suite. */
    private suspend fun playStop(current: Settings) {
        stopClip?.takeIf { it.isNotEmpty() }?.let {
            onSpeaking(STOP_WORD)
            player.play(it).onFailure { onSpeaking("") }
            return
        }
        val bytes = ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(STOP_WORD).getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isEmpty()) return
        stopClip = bytes
        onSpeaking(STOP_WORD)
        player.play(bytes).onFailure { onSpeaking("") }
    }

    companion object {
        /** Le seul mot critique, identique en français et en anglais. */
        const val STOP_WORD = "Stop."
    }
}
