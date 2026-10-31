package dev.sam3vision.app.speech

import dev.sam3vision.core.settings.Settings

/**
 * Phrases de navigation prononcées via le chemin TTS existant.
 *
 * Textes exacts du contrat : « Navigating to X » quand le guidage démarre,
 * « I can't open the guidance » quand il est impossible à ouvrir.
 * Pures et sans Android : testables en JVM (JUnit seul).
 */
object NavigationPhrases {

    /**
     * « Navigating to X » — [destination] brute, espaces rognés.
     * L'appelant refuse les destinations blanches avant de parler.
     */
    fun started(destination: String): String = "Navigating to ${destination.trim()}"

    /** Guidage impossible à ouvrir (aucune app de navigation, lancement refusé…). */
    const val GUIDANCE_UNAVAILABLE = "I can't open the guidance"
}

/**
 * Annonces vocales de navigation via le chemin TTS existant.
 *
 * Aucun moteur réécrit ici : la synthèse + lecture reste celle de
 * [dev.sam3vision.app.VoiceAnnouncer], injectée par [speak] — l'app passe
 * `voice::say`, les tests un espion — comme
 * [dev.sam3vision.app.live.LivePositionVoice]. Ce helper ne fait que choisir
 * la phrase ([NavigationPhrases]) et ne lève jamais ([Result]).
 *
 * @param settings réglages courants (clé et voix ElevenLabs), lus à chaque annonce.
 * @param speak synthèse puis lecture ; `suspend (Settings, String) -> Unit`.
 */
class NavigationAnnouncer(
    private val settings: () -> Settings,
    private val speak: suspend (Settings, String) -> Unit,
) {

    /**
     * Annonce « Navigating to X ». Une destination blanche est refusée par
     * [Result.failure] sans rien dire ; un [speak] qui jette devient aussi
     * [Result.failure] — jamais d'exception levée.
     */
    suspend fun announceStarted(destination: String): Result<Unit> {
        if (destination.isBlank()) return Result.failure(IllegalArgumentException("empty destination"))
        return runCatching { speak(settings(), NavigationPhrases.started(destination)) }
    }

    /**
     * Annonce « I can't open the guidance ». Ne lève jamais : un [speak]
     * qui jette est converti en [Result.failure].
     */
    suspend fun announceGuidanceUnavailable(): Result<Unit> =
        runCatching { speak(settings(), NavigationPhrases.GUIDANCE_UNAVAILABLE) }
}
