package dev.sam3vision.app

import dev.sam3vision.core.net.ElevenLabsSttClient
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.net.FallbackSpeechClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.net.SpeechClient
import dev.sam3vision.core.net.TtsClient
import dev.sam3vision.core.settings.Settings

/**
 * Règle unique du moteur de dictée de l'app : Meta ASR d'abord, repli ElevenLabs
 * Scribe dès que la clé existe, `null` quand aucun des deux n'est configuré.
 * Partagée par la dictée du chat ([AppViewModel]) et la voix du direct ([LiveVoice]).
 */
fun speechClient(settings: Settings): SpeechClient? {
    val meta = if (settings.asrConfigured) {
        MetaAsrClient(settings.asrBaseUrl, settings.asrApiKey, settings.asrModel)
    } else {
        null
    }
    val eleven = if (settings.ttsApiKey.isNotBlank()) ElevenLabsSttClient(settings.ttsApiKey) else null
    return when {
        meta != null && eleven != null -> FallbackSpeechClient(primary = meta, fallback = eleven)
        meta != null -> meta
        else -> eleven
    }
}

/**
 * Client de synthèse de l'app : ElevenLabs, clé et voix des réglages.
 *
 * Rien à revalider chez l'appelant : le client refuse lui-même un texte vide, une clé
 * absente ou une voix absente, avec un message déjà affichable
 * ([dev.sam3vision.core.net.ApiError.InvalidKey], [dev.sam3vision.core.net.ApiError.BadResponse]),
 * et sans aucun appel réseau dans ces trois cas. C'est la capacité synthèse qui manquait
 * ici — [speechClient] ne couvre que la dictée — et elle sert autant la voix du banc
 * lunettes (`GlassesVoice`) que celle de l'app ([VoiceAnnouncer]).
 */
fun ttsClient(settings: Settings): TtsClient =
    ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId)
