package dev.sam3vision.app.ihear.vm

import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings

/**
 * La voix de l'écran : la synthèse ElevenLabs d'un texte de bulle. Une seule
 * implémentation du client dans `:core`, jamais réécrite ici.
 *
 * Les octets vides signifient « rien à dire » : texte non synthétisable (marqueurs
 * audio seuls) ou clé absente — l'appelant affiche alors le texte sans audio.
 */
class IhearVoice(
    private val settings: () -> Settings,
    private val update: StateUpdate,
) {

    /** Synthèse du texte ; octets vides si la clé manque ou s'il n'y a rien à dire. */
    suspend fun synthesize(text: String): ByteArray {
        if (!speakable(text)) return ByteArray(0)
        val current = settings()
        return ElevenLabsTtsClient(current.ttsApiKey, current.ttsVoiceId)
            .synthesize(text)
            .getOrElse { error ->
                update { it.copy(status = IhearStatusText.voiceFailed(error)) }
                ByteArray(0)
            }
    }

    /** Marqueurs audio (« [silence] »…) retirés : reste-t-il une lettre ou un chiffre ? */
    private fun speakable(text: String): Boolean = dev.sam3vision.app.ihear.speakableText(text)
}