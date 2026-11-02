package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.IseeClipsEn
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Chaînes MP3 des comptes de marches (ordre user : ElevenLabs = LLM + noms
 * GPS, le reste en MP3) : le compte et le compte à rebours se disent en
 * atomes chaînés (cardinal + unité), joués en séquence au bus
 * ([IseeClipsEn.cardinal] + unités). Anglais seul (le pack ne porte que des
 * clips EN) : toute autre langue rend une liste vide (silence, jamais de
 * synthèse). Pur, testé en JVM.
 */
object StairChain {

    /** "N steps." / "1 step." → [cardinal, step/steps], vide si hors pack. */
    fun countClips(count: Int, language: SpeechLanguage): List<String> {
        if (language != SpeechLanguage.En) return emptyList()
        val number = IseeClipsEn.cardinal(count) ?: return emptyList()
        val unit = if (count == 1) IseeClipsEn.C_STEP else IseeClipsEn.C_STEPS
        return listOf(number, unit)
    }

    /** "N steps left." → [cardinal, steps_left], vide si hors pack. */
    fun countdownClips(remaining: Int, language: SpeechLanguage): List<String> {
        if (language != SpeechLanguage.En || remaining < 2) return emptyList()
        val number = IseeClipsEn.cardinal(remaining) ?: return emptyList()
        return listOf(number, IseeClipsEn.C_STEPS_LEFT)
    }
}
