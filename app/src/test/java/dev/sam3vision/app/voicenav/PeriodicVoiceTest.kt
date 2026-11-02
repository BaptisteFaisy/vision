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
        assertEquals("Straight on.", phrase(WalkDirective.Straight))
        assertEquals("Turn left.", phrase(WalkDirective.Left))
        assertEquals("Turn right.", phrase(WalkDirective.Right))
        assertEquals("Turn around.", phrase(WalkDirective.TurnAround))
        assertEquals("Cross now.", phrase(WalkDirective.Cross))
        assertEquals("Roundabout.", phrase(WalkDirective.Roundabout))
    }

    @Test
    fun `rappel relatif suit la langue de la voix`() {
        assertEquals("Tout droit.", phrase(WalkDirective.Straight, SpeechLanguage.Fr))
        assertEquals("À gauche.", phrase(WalkDirective.Left, SpeechLanguage.Fr))
        assertEquals("À droite.", phrase(WalkDirective.Right, SpeechLanguage.Fr))
    }

    private fun phrase(directive: WalkDirective, language: SpeechLanguage = SpeechLanguage.En): String =
        VoiceNavPhrases.phrase(periodicEventOf(directive), language)
}
