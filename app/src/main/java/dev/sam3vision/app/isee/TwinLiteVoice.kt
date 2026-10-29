package dev.sam3vision.app.isee

import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.core.segment.TwinLiteCue
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Canal vocal du guidage TwinLiteNet : l'annonceur décide (quoi, quand), le
 * [SystemTtsEngine] prononce (voix système, file sans chevauchement).
 *
 * Voie hors ligne : [TwinLiteCue.urgent] coupe la phrase en cours
 * (`QUEUE_FLUSH`), sinon mise en file derrière (`QUEUE_ADD`) — un virage ne
 * peut pas attendre une clé ni le réseau. Adaptateur fin, comme
 * [dev.sam3vision.app.speech.VoiceNavSpeaker] : les réglages ([language],
 * [rate]) sont fournis par l'appelant à chaque phrase, rien ne lève jamais
 * (le moteur ne lève jamais, voir [SystemTtsEngine]).
 */
class TwinLiteVoice(private val engine: SystemTtsEngine) {

    /**
     * Prononce [cue] : [language] est la langue de la voix (Réglages),
     * [rate] le débit (borné par [Settings.clampTtsSpeed] dans le moteur).
     * Vrai si le moteur a accepté la phrase.
     */
    fun say(
        cue: TwinLiteCue,
        language: SpeechLanguage = SpeechLanguage.DEFAULT,
        rate: Float = Settings.DEFAULT_TTS_SPEED,
    ): Boolean {
        val locale = SystemTtsEngine.localeOf(language)
        return engine.speak(cue.phrase, cue.urgent, locale, rate)
    }
}
