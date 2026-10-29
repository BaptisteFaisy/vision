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
 * [urgent] = danger (couloir bouché, trottoir, escaliers) : coupe la lecture
 * en cours et parle quand même, y compris pendant une dictée — la sécurité
 * passe avant le sous-titre (miroir de `TraverseVoice`, qui préempte la verte
 * par la rouge). [synthesize] injecte le moteur (ElevenLabs en prod, faux en
 * test — miroir `IseePlayback`).
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text -> ElevenLabsTtsClient(apiKey, voiceId).synthesize(text) },
) {

    private var lastPhrase: String? = null

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
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
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, phrase)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && (urgent || canPlay())) {
            onSpeaking(phrase)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }
}
