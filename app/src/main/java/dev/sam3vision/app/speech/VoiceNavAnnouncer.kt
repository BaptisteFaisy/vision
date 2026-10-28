package dev.sam3vision.app.speech

import dev.sam3vision.core.live.AnnouncementGate
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Annonces vocales du guidage embarqué, deux canaux dans une seule classe.
 *
 * Canal moteur ([onEvent]) : [VoiceNavPolicy] décide (nouveauté, urgence),
 * [AnnouncementGate] retient (délai), [VoiceNavPhrases] met en mots — la
 * phrase à prononcer, ou `null` s'il faut garder le silence. [minFrames]
 * vaut 1, pas 2 : les événements sont discrets (un virage émis une fois
 * doit se dire), pas des frames à 2 ips dont il faut lisser le tremblé.
 *
 * Canal session ([announceHeard] et sœurs) : annonces de la navigation à
 * confirmation via le chemin TTS existant — même motif que
 * [NavigationAnnouncer] : synthèse + lecture injectées par [speak] (l'app
 * passe `voice::say`, les tests un espion). Ne lève jamais ([Result]).
 *
 * @param settings réglages courants (clé et voix), lus à chaque annonce.
 * @param speak synthèse puis lecture ; `suspend (Settings, String) -> Unit`
 *   (en dernier : les appels gardent la forme `(réglages) { dire }`).
 */
class VoiceNavAnnouncer(
    private val settings: () -> Settings = { Settings.Default },
    minFrames: Int = 1,
    cooldownMs: Long = AnnouncementGate.DEFAULT_COOLDOWN_MS,
    private val speak: suspend (Settings, String) -> Unit = { _, _ -> },
) {

    private val gate = AnnouncementGate(minFrames, cooldownMs)

    /** Dernière phrase produite (`null` tant que rien n'a été dit). */
    val lastPhrase: String? get() = gate.lastPhrase

    /**
     * Événement survenu à [atMs] : la phrase à prononcer, ou `null` s'il faut
     * garder le silence (répétition, délai, départ sans destination).
     * [language] est la langue de la voix (Réglages).
     */
    fun onEvent(
        atMs: Long,
        event: VoiceNavEvent,
        language: SpeechLanguage = SpeechLanguage.Fr,
    ): String? {
        if (event is VoiceNavEvent.Started && event.destination.isBlank()) return null
        val key = VoiceNavPolicy.key(event)
        if (!gate.admit(atMs, key, urgent = VoiceNavPolicy.isUrgent(event))) return null
        val phrase = VoiceNavPhrases.phrase(event, language)
        gate.markSpoken(key, atMs, phrase)
        return phrase
    }

    /** Nouvelle session : tout est oublié (le prochain départ sera dit). */
    fun reset() = gate.reset()

    /** `runCatching` suspendu : `speak` est `suspend`, `runCatching` ne l'est pas. */
    private suspend fun said(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (t: Exception) {
        Result.failure(t)
    }

    /** Annonce « Destination : X ». Destination blanche refusée, sans rien dire. */
    suspend fun announceHeard(destination: String): Result<Unit> {
        if (destination.isBlank()) return Result.failure(IllegalArgumentException("destination vide"))
        return said { speak(settings(), VoiceNavPhrases.destinationHeard(destination)) }
    }

    /** Annonce « Voici la fiche de X ». Destination blanche refusée, sans rien dire. */
    suspend fun announcePlace(destination: String): Result<Unit> {
        if (destination.isBlank()) return Result.failure(IllegalArgumentException("destination vide"))
        return said { speak(settings(), VoiceNavPhrases.placeOpened(destination)) }
    }

    /** Annonce « Guidage arrêté ». Ne lève jamais. */
    suspend fun announceStopped(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.STOP_DONE) }

    /** Annonce « Arrivée à destination ». Ne lève jamais. */
    suspend fun announceArrived(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.ARRIVED) }
}
