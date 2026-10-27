package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.core.net.ElevenLabsTtsClient
import dev.sam3vision.core.settings.Settings
import java.io.File

/**
 * Voix de l'app : **ElevenLabs uniquement** (pas de moteur TTS système).
 *
 * Synthèse par [ElevenLabsTtsClient] avec la clé des réglages, lecture du MP3 par
 * [SpeechPlayer]. Le texte à lire est passé tel quel : c'est l'appelant qui décide
 * quoi annoncer (bandeau « J'écoute », résultat, erreurs).
 *
 * Un échec (clé absente, 402, réseau) reste silencieux côté audio : l'information
 * est déjà affichée en gros caractères et exposée à TalkBack par le bandeau.
 */
class VoiceAnnouncer(context: Context, dirName: String = DEFAULT_DIR) {

    private val player = SpeechPlayer(File(context.cacheDir, dirName))

    /** Synthétise puis lit [text] ; sans effet si le texte ou la clé sont vides. */
    suspend fun say(settings: Settings, text: String) {
        val clean = text.trim()
        if (clean.isEmpty() || settings.ttsApiKey.isBlank()) return
        player.speed = settings.ttsSpeed
        ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId).synthesize(clean).onSuccess { audio ->
            player.play(audio)
        }
    }

    fun stop() = player.stop()

    fun release() = player.release()

    companion object {
        /** Dossier par défaut (bandeaux de dictée) ; un second dossier évite d'écraser son MP3. */
        const val DEFAULT_DIR = "voice"
    }
}