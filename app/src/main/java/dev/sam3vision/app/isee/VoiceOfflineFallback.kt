package dev.sam3vision.app.isee

import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Repli hors-ligne de la voix douce : sans clé ElevenLabs (ou voix réseau
 * en panne : 401/402/429, [isNetworkVoiceDown]), la phrase part au TTS
 * système ([system], en file) au lieu de se taire — précédée une fois par
 * session du signal [networkVoiceDown]. Oral seul (ni sous-titre, ni
 * fanion — miroir `SafetyVoice` : sans fin de lecture, un fanion bloquerait
 * la voix douce pour toujours), mais mémorisé via [onSpoken] (pas de
 * répétition). Extrait de `ProximityVoice` (plafond 500 lignes). Testé en
 * JVM derrière un pilote faux.
 */
internal class VoiceOfflineFallback(
    private val system: SystemTtsEngine?,
    private val onSpoken: (String) -> Unit = {},
) {
    /** Signal « voix réseau indisponible » déjà parlé (une fois par session). */
    private var networkAlertSpoken = false

    /** Nouvelle session : le signal de repli se redira devant la prochaine phrase. */
    fun reset() {
        networkAlertSpoken = false
    }

    /**
     * Dit [phrase] au TTS système, en file. Le premier appel de la session
     * est précédé du signal [networkVoiceDown] (combiné en une seule parole :
     * l'ordre est atomique). `true` si confié au moteur ([onSpoken] appelé) ;
     * `false` si aucun moteur câblé ou refus (non mémorisé : réémis, il se dira).
     */
    fun speak(phrase: String, current: Settings): Boolean {
        val alerted = networkAlertSpoken
        val utterance =
            if (alerted) phrase else "${networkVoiceDown(current.speechLanguage)} $phrase"
        val engine = system ?: return false
        val locale = SystemTtsEngine.localeOf(current.speechLanguage)
        if (!engine.speak(utterance, false, locale, current.ttsSpeed, 1f)) return false
        networkAlertSpoken = true
        onSpoken(phrase)
        return true
    }

    companion object {
        /**
         * Signal unique de repli (« Voix réseau indisponible. » /
         * « Network voice unavailable. ») : parlé une fois par session devant
         * la première phrase dite au TTS système.
         */
        fun networkVoiceDown(language: SpeechLanguage): String = when (language) {
            SpeechLanguage.Fr -> "Voix réseau indisponible."
            SpeechLanguage.En -> "Network voice unavailable."
        }

        /** Voix réseau en panne durable (clé, crédits, cadence) : le repli système prend le relais. */
        fun isNetworkVoiceDown(error: Throwable): Boolean = when (error) {
            is ApiError.InvalidKey, is ApiError.PaymentRequired, is ApiError.RateLimited -> true
            else -> false
        }
    }
}
