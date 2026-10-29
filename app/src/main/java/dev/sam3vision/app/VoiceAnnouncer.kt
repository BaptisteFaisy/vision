package dev.sam3vision.app

import android.content.Context
import dev.sam3vision.app.glasses.GlassesVoiceRoute
import dev.sam3vision.core.net.ApiError
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
 * Un échec (clé absente, 402, réseau, lecture) est rendu à l'appelant, jamais avalé :
 * écran verrouillé, personne ne lit le bandeau — un succès mensonger y serait un
 * silence total. Les appelants « bandeau » ignorent le résultat (l'info est déjà
 * affichée en gros caractères et exposée à TalkBack) ; le micro verrouillé le publie.
 */
class VoiceAnnouncer(context: Context, dirName: String = DEFAULT_DIR) {

    private val player = SpeechPlayer(
        dir = File(context.cacheDir, dirName),
        // Lunettes en session : la phrase sort dans la paire, sinon sur le téléphone.
        output = { GlassesVoiceRoute.current(context) },
    )

    /**
     * Synthétise puis lit [text]. Rien à dire (texte vide) = succès ; clé absente,
     * synthèse ou lecture en échec = l'erreur typée du réseau ou du lecteur.
     */
    suspend fun say(settings: Settings, text: String): Result<Unit> {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.success(Unit)
        if (settings.ttsApiKey.isBlank()) {
            return Result.failure(ApiError.InvalidKey("clé ElevenLabs absente — renseigne-la dans Réglages"))
        }
        player.speed = settings.ttsSpeed
        val audio = ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId)
            .synthesize(clean).getOrElse { return Result.failure(it) }
        return player.play(audio)
    }

    fun stop() = player.stop()

    fun release() = player.release()

    companion object {
        /** Dossier par défaut (bandeaux de dictée) ; un second dossier évite d'écraser son MP3. */
        const val DEFAULT_DIR = "voice"
    }
}
