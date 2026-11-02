package dev.sam3vision.app.voicenav

import dev.sam3vision.app.speech.VoiceNavPhrases
import dev.sam3vision.core.nav.WalkDirective
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phrases du rappel 5 s ([periodicEventOf]) : mot anglais seul, jamais
 * de rue ni de distance — les lignes droites sont silencieuses.
 */
class PeriodicVoiceTest {

    @Test
    fun `rappel anglais sans rue ni distance`() {
        assertEquals("", phrase(WalkDirective.Straight))
        assertEquals("Left.", phrase(WalkDirective.Left))
        assertEquals("Right.", phrase(WalkDirective.Right))
        assertEquals("Turn around.", phrase(WalkDirective.TurnAround))
        assertEquals("Cross now.", phrase(WalkDirective.Cross))
        assertEquals("Roundabout.", phrase(WalkDirective.Roundabout))
    }

    @Test
    fun `rappel relatif mot anglais seul`() {
        assertEquals("", phrase(WalkDirective.Straight, SpeechLanguage.Fr))
        assertEquals("Left.", phrase(WalkDirective.Left, SpeechLanguage.Fr))
        assertEquals("Right.", phrase(WalkDirective.Right, SpeechLanguage.Fr))
    }

    private fun phrase(directive: WalkDirective, language: SpeechLanguage = SpeechLanguage.En): String =
        VoiceNavPhrases.phrase(periodicEventOf(directive), language)
}
