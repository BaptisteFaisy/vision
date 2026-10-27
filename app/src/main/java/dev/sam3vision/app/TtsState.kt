package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * État de la voix (ElevenLabs) : inactif, synthèse en cours, lecture, terminé, ou erreur.
 * Chaque état actif retient [text] (la phrase concernée) pour qu'un seul bouton — celui
 * qui a déclenché la lecture — affiche son état, sans bruit sur les autres bulles.
 */
sealed interface SpeakState {
    data object Idle : SpeakState
    data class Synthesizing(val text: String) : SpeakState
    data class Playing(val text: String) : SpeakState
    data class Done(val text: String) : SpeakState
    data class Error(val text: String, val message: String) : SpeakState
}

/**
 * Glyphes des boutons. `material-icons-core`, seule variante d'icônes résolvable
 * hors-ligne, ne contient pas VolumeUp/VolumeOff : pas de nouvelle dépendance.
 */
const val SPEAK_GLYPH = "🔊"
const val STOP_GLYPH = "⏹"

/** Phrase envoyée par « Tester la voix » dans les réglages, dans la langue de la voix. */
fun testVoiceText(language: SpeechLanguage = SpeechLanguage.Fr): String = when (language) {
    SpeechLanguage.Fr -> "Test de voix SAM Vision."
    SpeechLanguage.En -> "This is the SAM Vision voice."
}

const val SPEAK_TEXT_MISSING = "rien à lire : texte vide"
const val SPEAK_NO_KEY = "clé ElevenLabs absente — renseigne-la dans Réglages"
const val SPEAK_TIMEOUT = "voix ElevenLabs : délai dépassé — réessaie"

/** Au-delà, la synthèse est abandonnée : jamais d'état « en cours » sans fin. */
const val SPEAK_TIMEOUT_MS = 20_000L

/**
 * Refus local, avant tout appel réseau : message affichable, ou null si on peut parler.
 * [ttsConfigured] vient de `Settings.ttsConfigured` (clé + voix renseignées).
 */
fun speakRefusal(text: String, ttsConfigured: Boolean): String? = when {
    text.isBlank() -> SPEAK_TEXT_MISSING
    !ttsConfigured -> SPEAK_NO_KEY
    else -> null
}

/** Vrai si [state] concerne [text] et qu'une lecture est en cours (bouton « stop »). */
fun speakIsActive(state: SpeakState, text: String): Boolean = when (state) {
    is SpeakState.Synthesizing -> sameSpeech(state.text, text)
    is SpeakState.Playing -> sameSpeech(state.text, text)
    else -> false
}

/** Libellé court affiché à côté du bouton, ou null quand il n'y a rien à dire. */
fun speakLabel(state: SpeakState, text: String): String? = when (state) {
    SpeakState.Idle -> null
    is SpeakState.Synthesizing -> if (sameSpeech(state.text, text)) "voix en cours…" else null
    is SpeakState.Playing -> if (sameSpeech(state.text, text)) "lecture en cours…" else null
    is SpeakState.Done -> if (sameSpeech(state.text, text)) "voix OK" else null
    is SpeakState.Error -> if (sameSpeech(state.text, text)) state.message else null
}

/** Deux textes désignent la même phrase (la synthèse travaille sur le texte découpé). */
fun sameSpeech(stored: String, candidate: String): Boolean = stored == candidate.trim()

/** Complète l'ID de voix vide par le défaut ElevenLabs : jamais de champ vide persisté. */
fun withTtsDefaults(settings: Settings): Settings =
    if (settings.ttsVoiceId.isBlank()) settings.copy(ttsVoiceId = Settings.DEFAULT_TTS_VOICE_ID) else settings

/** Un cran de vitesse (±0,1), arrondi au dixième et borné 0,5..2 (pas de dérive flottante). */
fun bumpTtsSpeed(current: Float, delta: Float): Float {
    val rounded = kotlin.math.round((current + delta) * 10) / 10f
    return Settings.clampTtsSpeed(rounded)
}

/** Vitesse affichée dans les Réglages (« ×1,9 », « ×2 »). */
fun formatTtsSpeed(speed: Float): String {
    val rounded = kotlin.math.round(Settings.clampTtsSpeed(speed) * 10) / 10f
    val digits = if (rounded % 1 == 0f) rounded.toInt().toString() else rounded.toString()
    return "×" + digits.replace('.', ',')
}