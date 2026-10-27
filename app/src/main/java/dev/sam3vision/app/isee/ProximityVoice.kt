package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings

/**
 * Voix de proximité de la fenêtre caméra : dit **une fois** chaque nouvelle phrase de
 * [LiveProbe] (« Personne à 9 heures, à 1,4 mètre »).
 *
 * Trois garde-fous : la même phrase n'est jamais répétée, rien n'est dit sans clé
 * ElevenLabs, et jamais par-dessus la réponse du chat (une seule source sonore à la
 * fois — c'est l'appelant qui dit si le lecteur est libre).
 */
class ProximityVoice(private val player: SpeechPlayer, private val settings: () -> Settings) {

    private var lastPhrase: String? = null

    /** Nouvelle session : la prochaine phrase sera dite même si elle ressemble à la précédente. */
    fun reset() {
        lastPhrase = null
    }

    /**
     * Nouvelle phrase de SAM. [playerIdle] vrai = aucune bulle du chat en cours de lecture ;
     * sinon on ne dit rien (et la phrase reste mémorisée comme déjà traitée, pour ne pas
     * la dire en retard une fois le chat terminé).
     */
    suspend fun onPhrase(phrase: String, playerIdle: Boolean) {
        if (phrase == lastPhrase) return
        lastPhrase = phrase
        val current = settings()
        if (!playerIdle || !current.ttsConfigured) return
        val bytes = ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(phrase)
            .getOrElse { return }
        if (bytes.isNotEmpty()) player.play(bytes)
    }
}