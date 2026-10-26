package dev.sam3vision.app

import dev.sam3vision.core.net.ElevenLabsSttClient
import dev.sam3vision.core.net.FallbackSpeechClient
import dev.sam3vision.core.net.MetaAsrClient
import dev.sam3vision.core.net.SpeechClient
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
