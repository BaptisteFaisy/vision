package dev.sam3vision.core.live

import dev.sam3vision.core.distance.DistanceText
import dev.sam3vision.core.speech.SpeechLanguage

/**
 * Consigne affichée du battement : le mot du pulse (« Gauche ») plus le plus
 * proche droit devant (« ≈ 2,1 m ») — la phrase que la voix dirait, en version
 * lue. Périmé : préfixe « … » (donnée vieille, pas de chiffre frais). Pur et
 * testé en JVM.
 */
object GuidanceInstruction {
    /** Ligne affichée : mot seul sans mesure, « … » si périmé. */
    fun text(pulse: GuidancePulse, language: SpeechLanguage = SpeechLanguage.Fr): String {
        val word = pulse.word.text(language).removeSuffix(".")
        val dist = pulse.nearestMeters?.takeIf { it.isFinite() }
            ?.let { " · ${DistanceText.approximate(it)}" }.orEmpty()
        val line = word + dist
        return if (pulse.stale) "… $line" else line
    }
}
