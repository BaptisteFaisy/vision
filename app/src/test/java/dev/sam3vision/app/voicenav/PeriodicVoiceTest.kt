package dev.sam3vision.app.voicenav

import dev.sam3vision.app.speech.VoiceNavPhrases
import dev.sam3vision.core.nav.WalkDirective
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phrases du rappel 5 s ([periodicEventOf]) : anglais, jamais de rue ni
 * de distance — les consignes relatives suivent la langue de la voix.
 */
class PeriodicVoiceTest {

    @Test
    fun `rappel anglais sans rue ni distance`() {
        assertEquals("Continue straight ahead.", phrase(WalkDirective.Straight))
        assertEquals("Turn left.", phrase(WalkDirective.Left))
        assertEquals("Turn right.", phrase(WalkDirective.Right))
        assertEquals("Turn around.", phrase(WalkDirective.TurnAround))
        assertEquals("Cross the street.", phrase(WalkDirective.Cross))
        assertEquals("At the roundabout, follow the road.", phrase(WalkDirective.Roundabout))
    }

    @Test
    fun `rappel relatif suit la langue de la voix`() {
        assertEquals("Continuez tout droit.", phrase(WalkDirective.Straight, SpeechLanguage.Fr))
        assertEquals("Tournez à gauche.", phrase(WalkDirective.Left, SpeechLanguage.Fr))
        assertEquals("Tournez à droite.", phrase(WalkDirective.Right, SpeechLanguage.Fr))
    }

    private fun phrase(directive: WalkDirective, language: SpeechLanguage = SpeechLanguage.En): String =
        VoiceNavPhrases.phrase(periodicEventOf(directive), language)
}
