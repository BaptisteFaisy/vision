package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
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
 * [urgent] = stop (mot du guidage ou modèle d'urgence externe) : coupe la
 * lecture en cours et parle quand même, y compris pendant une dictée — la
 * sécurité passe avant le sous-titre (miroir de `TraverseVoice`, qui
 * préempte la verte par la rouge). Les mots doux et les « Attention … »
 * attendent le silence. [synthesize] injecte le moteur (ElevenLabs en prod,
 * faux en test — miroir `IseePlayback`). [clips] est le pack voix (mots du
 * guidage) : clip local quand il existe (< 100 ms, zéro réseau), synthèse
 * + mise en cache sinon (`null` = synthèse seule, comme avant).
 */
class ProximityVoice(
    private val player: SpeechPlayer,
    private val settings: () -> Settings,
    private val onSpeaking: (String) -> Unit = {},
    private val canPlay: () -> Boolean = { true },
    private val synthesize: suspend (apiKey: String, voiceId: String, text: String) -> Result<ByteArray> =
        { apiKey, voiceId, text -> ElevenLabsTtsClient(apiKey, voiceId).synthesize(text) },
    private val clips: VoicePack? = null,
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

    /**
     * Mot du guidage (`GuidanceWord`) : clip local quand le pack l'a (< 100
     * ms, zéro réseau), sinon synthèse réseau mise en cache pour la suite.
     * Mêmes garde-fous que [onPhrase] (dédupliqué, clé exigée, urgent coupe).
     */
    suspend fun speakWord(
        clipId: String,
        text: String,
        language: SpeechLanguage,
        playerIdle: Boolean,
        urgent: Boolean = true,
    ) {
        if (text == lastPhrase) return
        lastPhrase = text
        val current = settings()
        if (!current.ttsConfigured) return
        if (!urgent && !playerIdle) return
        if (urgent) player.stop()
        val file = clips?.clipFor(clipId, language, current.ttsVoiceId)?.takeIf { it.isFile }
        if (file != null) {
            coroutineContext.ensureActive()
            onSpeaking(text)
            player.playFile(file).onFailure { onSpeaking("") }
            return
        }
        val bytes = synthesize(current.ttsApiKey, current.ttsVoiceId, text)
            .getOrElse { return }
        coroutineContext.ensureActive()
        if (bytes.isNotEmpty() && (urgent || canPlay())) {
            clips?.put(clipId, language, current.ttsVoiceId, bytes)
            onSpeaking(text)
            player.play(bytes).onFailure { onSpeaking("") }
        }
    }

    /**
     * Stop d'urgence injecté par le modèle d'urgence externe (ordre user du
     * 29/10, contrat `docs/URGENCE-EXTERNE.md`) : « Stop. » dit aussitôt, en
     * clip local (< 100 ms) quand le pack l'a, coupe tout (chat compris).
     * Dédupliqué comme le reste : un « Stop. » déjà dit ne se répète pas (le
     * stop interne, contact ≤ 0,6 m, est gardé en attendant — même mot, pas
     * de conflit).
     */
    suspend fun reportEmergency(language: SpeechLanguage, playerIdle: Boolean) {
        val stop = GuidanceWord.Stop
        speakWord(stop.clipId, stop.text(language), language, playerIdle, urgent = true)
    }
}
