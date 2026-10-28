package dev.sam3vision.app.tap

import android.content.Context
import dev.sam3vision.app.BuildConfig
import dev.sam3vision.core.settings.Settings
import java.io.File

/**
 * Les trois — et seuls trois — mots prononcés par le mode tap. Un appui court sur l'écran
 * lance l'enregistrement (« Launched »), le second l'arrête et l'envoie (« Sent ») ;
 * permission refusée, caméra indisponible ou échec d'envoi → « Failed ».
 */
enum class Announcement(val spoken: String) {
    LAUNCHED("Launched"),
    SENT("Sent"),
    FAILED("Failed"),
    ;

    /** Nom du clip en cache : `launched.mp3`, `sent.mp3`, `failed.mp3`. */
    val clipFile: String get() = name.lowercase() + ".mp3"
}

/**
 * Fichier de config central du mode tap. Tout ce qui change d'un déploiement à l'autre est ici :
 *
 * - `SAM31_ENDPOINT` ← `local.properties` (`sam31.endpoint`) ;
 * - `ELEVENLABS_API_KEY` ← Réglages (store chiffré) sinon `local.properties` (`tts.api.key`) ;
 * - `LAUNCHED` / `SENT` / `FAILED` ← [Announcement].
 *
 * Aucune clé n'est écrite en dur dans le code.
 */
object TapConfig {

    /** Endpoint SAM 3.1 vidéo. Vide = envoi impossible → annonce « Failed ». */
    val sam31Endpoint: String get() = BuildConfig.SAM31_ENDPOINT.trim()

    /**
     * Piste audio de la vidéo. **Faux par défaut** : Android n'autorise qu'un seul
     * `AudioRecord` par app, donc une vidéo qui prend le micro interdit la dictée pendant
     * l'enregistrement. L'analyse demandée à SAM 3.1 est visuelle ([UPLOAD_PROMPT] est
     * fixe) : la vidéo se contente de l'image et laisse le micro à la voix.
     * Passer à `true` rend le mode tap sonore mais rétablit l'arbitrage [dev.sam3vision.app.MicBus]
     * (la dictée sera alors refusée « micro occupé » pendant la capture).
     */
    const val VIDEO_WITH_AUDIO: Boolean = false

    /** Mode « SAM 3.1 tourne sur le téléphone » : l'upload cloud est alors désactivé. */
    const val ON_DEVICE_INFERENCE: Boolean = false

    /** Consigne jointe à la vidéo (le modèle est interrogé en anglais). */
    const val UPLOAD_PROMPT: String = "Describe what happens in this video."

    /** Au-delà de ce délai réseau, le clip est stocké localement et rejoué depuis le cache. */
    const val NETWORK_LATENCY_BUDGET_MS: Long = 500L

    /** Durée maximale d'un enregistrement : au-delà, arrêt + envoi automatiques. */
    const val MAX_RECORDING_MS: Long = 60_000L

    /**
     * Durée minimale d'un clip. En dessous (~300 ms mesuré sur émulateur), CameraX finalise la
     * prise en **erreur 8 avec un fichier de 0 octet** : l'envoi échouerait mécaniquement. Un tap
     * arrivé plus tôt n'est pas perdu — il est mémorisé et exécuté dès que cette durée est atteinte.
     */
    const val MIN_RECORDING_MS: Long = 350L

    /** Un clip déjà généré est réutilisé tant qu'il est plus vieux que 30 jours. */
    const val CLIP_TTL_MS: Long = 30L * 24L * 60L * 60L * 1000L

    /** Les trois mots, dans l'ordre d'apparition à l'écran. */
    val words: List<Announcement> get() = Announcement.entries.toList()

    /** Clé ElevenLabs : saisie Réglages d'abord, sinon valeur de debug (`local.properties`). */
    fun elevenLabsKey(settings: Settings): String = settings.ttsApiKey.ifBlank { BuildConfig.TTS_API_KEY.trim() }

    /** Voix ElevenLabs : Réglages, puis debug, puis la premade par défaut de l'app. */
    fun elevenLabsVoice(settings: Settings): String = when {
        settings.ttsVoiceId.isNotBlank() -> settings.ttsVoiceId
        BuildConfig.TTS_VOICE_ID.isNotBlank() -> BuildConfig.TTS_VOICE_ID
        else -> Settings.DEFAULT_TTS_VOICE_ID
    }

    /** Vrai si l'endpoint vidéo est joignable tel que configuré. */
    val sendConfigured: Boolean get() = sendBlockedReason() == null

    /**
     * `null` quand l'envoi est possible, sinon la raison explicite affichée à l'écran
     * (jamais un « Failed » silencieux). Paramètres injectables : les tests unitaires
     * passent leurs valeurs, la prod lit le [BuildConfig].
     */
    fun sendBlockedReason(
        endpoint: String = sam31Endpoint,
        onDevice: Boolean = ON_DEVICE_INFERENCE,
    ): String? = when {
        onDevice -> "SAM 3.1 configuré pour l'inférence locale : envoi cloud désactivé"
        endpoint.isBlank() -> "endpoint SAM 3.1 vide : renseigne sam31.endpoint dans local.properties"
        else -> null
    }

    /**
     * Dossier privé des clips, un par voix : un changement de voix (Sarah hier,
     * Laure aujourd'hui) régénère les trois mots au lieu de rejouer l'ancienne voix
     * jusqu'à la fin du TTL. Hors stockage partagé.
     */
    fun clipDir(context: Context, voiceId: String): File =
        File(context.filesDir, "announcements-${voiceId.take(8).ifBlank { "defaut" }}")
}
