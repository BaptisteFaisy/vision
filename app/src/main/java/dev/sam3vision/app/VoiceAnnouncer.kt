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
 *
 * Trace voix ([onEvent], défaut muet) : `EXPECT` quand une phrase est demandée
 * (texte vide = succès silencieux, rien n'est attendu), `FAIL` à chaque échec.
 * Le lecteur interne relaie ses `play`/`stop`/`done`/`cut` au même canal : un
 * succès se lit `EXPECT`, `stop`, `play`, `done`.
 */
class VoiceAnnouncer {

    private val player: SpeechPlayer
    private val onEvent: (String) -> Unit

    constructor(context: Context, dirName: String = DEFAULT_DIR, onEvent: (String) -> Unit = {}) : this(
        SpeechPlayer(
            dir = File(context.cacheDir, dirName),
            // Lunettes en session : la phrase sort dans la paire, sinon sur le téléphone.
            output = { GlassesVoiceRoute.current(context) },
            onEvent = onEvent,
        ),
        onEvent,
    )

    /**
     * Couture de test JVM (`Context` indisponible hors Android) : lecteur
     * direct, même routage des événements — la prod passe par le [Context].
     */
    internal constructor(player: SpeechPlayer, onEvent: (String) -> Unit = {}) {
        this.player = player
        this.onEvent = onEvent
    }

    /**
     * Synthétise puis lit [text]. Rien à dire (texte vide) = succès ; clé absente,
     * synthèse ou lecture en échec = l'erreur typée du réseau ou du lecteur.
     */
    suspend fun say(settings: Settings, text: String): Result<Unit> {
        val clean = text.trim()
        if (clean.isEmpty()) return Result.success(Unit)
        onEvent("EXPECT")
        if (settings.ttsApiKey.isBlank()) {
            return fail(ApiError.InvalidKey("ElevenLabs key missing — enter it in Settings"))
        }
        player.speed = settings.ttsSpeed
        val audio = ElevenLabsTtsClient(settings.ttsApiKey, settings.ttsVoiceId, cache = ElevenLabsTtsClient.sharedPhrases)
            .synthesize(clean).getOrElse { return fail(it) }
        return player.play(audio).onFailure { onEvent("FAIL") }
    }

    /** Échec tracé : l'erreur typée part à l'appelant, jamais un succès mensonger. */
    private fun fail(cause: Throwable): Result<Unit> {
        onEvent("FAIL")
        return Result.failure(cause)
    }

    fun stop() = player.stop()

    fun release() = player.release()

    companion object {
        /** Dossier par défaut (bandeaux de dictée) ; un second dossier évite d'écraser son MP3. */
        const val DEFAULT_DIR = "voice"
    }
}
