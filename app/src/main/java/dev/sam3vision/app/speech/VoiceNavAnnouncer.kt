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
 * Canal session ([announceConfirmQuestion] et sœurs) : annonces de la navigation à
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
     * [language] est la langue de la voix (Réglages). Admise ET marquée :
     * raccourci [admitEvent] + [markSpoken] (moteur synchrone, refus
     * impossible) ; le canal qui parle après coup ([VoiceNavSpeaker])
     * admet, parle, puis marque — un refus moteur ne marque pas.
     */
    fun onEvent(
        atMs: Long,
        event: VoiceNavEvent,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        val phrase = admitEvent(atMs, event, language) ?: return null
        markSpoken(event, atMs, phrase)
        return phrase
    }

    /**
     * Événement admis à [atMs] ([VoiceNavPolicy]) : la phrase à prononcer,
     * ou `null` s'il faut garder le silence — SANS marquer. L'appelant
     * parle, puis confirme par [markSpoken] : une phrase refusée par le
     * moteur (fermé, en panne) n'est pas marquée dite et se ré-admet à sa
     * réémission au lieu d'être perdue.
     */
    fun admitEvent(
        atMs: Long,
        event: VoiceNavEvent,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
    ): String? {
        if (event is VoiceNavEvent.Started && event.destination.isBlank()) return null
        val key = VoiceNavPolicy.key(event)
        if (!gate.admit(atMs, key, urgent = VoiceNavPolicy.isUrgent(event))) return null
        return VoiceNavPhrases.phrase(event, language)
    }

    /**
     * Confirme la parole moteur : [phrase] (admise par [admitEvent]) a été
     * prononcée à [atMs] — marquée dite (nouveauté, délai, [lastPhrase]).
     */
    fun markSpoken(event: VoiceNavEvent, atMs: Long, phrase: String) {
        gate.markSpoken(VoiceNavPolicy.key(event), atMs, phrase)
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

    /**
     * Question « Do you really want to go to X? … ». Destination blanche
     * refusée, sans rien dire ; [durationEn] `null` = question sans le temps.
     */
    suspend fun announceConfirmQuestion(destination: String, durationEn: String?): Result<Unit> {
        if (destination.isBlank()) return Result.failure(IllegalArgumentException("destination vide"))
        return said { speak(settings(), VoiceNavPhrases.confirmQuestion(destination, durationEn)) }
    }

    /** Annulation vocale (« no ») : « Cancelled. ». Ne lève jamais. */
    suspend fun announceConfirmCancelled(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.CONFIRM_CANCELLED) }

    /** « yes » pendant le chargement : « …still loading… ». Ne lève jamais. */
    suspend fun announceConfirmLoading(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.CONFIRM_LOADING) }

    /** Annonce « Voici la fiche de X ». Destination blanche refusée, sans rien dire. */
    suspend fun announcePlace(destination: String): Result<Unit> {
        if (destination.isBlank()) return Result.failure(IllegalArgumentException("destination vide"))
        return said { speak(settings(), VoiceNavPhrases.placeOpened(destination)) }
    }

    /** Annonce « Guidage arrêté ». Ne lève jamais. */
    suspend fun announceStopped(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.STOP_DONE) }

    /** Accusé « GPS désactivé » (ordre honoré). Ne lève jamais. */
    suspend fun announceDisabled(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.GPS_DISABLED) }

    /** Accusé « GPS activé » (ordre honoré). Ne lève jamais. */
    suspend fun announceEnabled(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.GPS_ENABLED) }

    /** Annonce « Arrivée à destination ». Ne lève jamais. */
    suspend fun announceArrived(): Result<Unit> =
        said { speak(settings(), VoiceNavPhrases.ARRIVED) }
}
