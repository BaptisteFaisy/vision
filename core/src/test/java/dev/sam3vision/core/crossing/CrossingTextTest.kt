package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingTextTest {

    @Test
    fun `phrases francaises courtes, rouge vert`() {
        assertEquals("Rouge.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.Fr))
        assertEquals("Vert.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.Fr))
    }

    @Test
    fun `phrases anglaises courtes, binaire rouge vert`() {
        assertEquals("Don't cross yet", CrossingText.phrase(CrossingState.Red, SpeechLanguage.En))
        assertEquals("You can cross", CrossingText.phrase(CrossingState.Green, SpeechLanguage.En))
    }

    @Test
    fun `le clignotant dit Attends`() {
        assertEquals("Attends.", CrossingText.blinking(SpeechLanguage.Fr))
        assertEquals("Don't go.", CrossingText.blinking(SpeechLanguage.En))
        assertEquals("Don't go.", CrossingText.blinking())
    }

    @Test
    fun `le pret dit pret, pas traversez`() {
        assertEquals("Prêt.", CrossingText.ready(SpeechLanguage.Fr))
        assertEquals("Ready.", CrossingText.ready(SpeechLanguage.En))
        assertEquals("Ready.", CrossingText.ready())
    }

    @Test
    fun `la contradiction dit feux mixtes`() {
        assertEquals("Feux mixtes.", CrossingText.contradiction(SpeechLanguage.Fr))
        assertEquals("Mixed lights.", CrossingText.contradiction(SpeechLanguage.En))
        assertEquals("Mixed lights.", CrossingText.contradiction())
    }

    @Test
    fun `l'anglais est le defaut des fonctions pures`() {
        assertEquals("Don't cross yet", CrossingText.phrase(CrossingState.Red))
    }

    @Test
    fun `la voix traversee passe par le TTS systeme`() {
        assertEquals(0.5f, CrossingText.VOICE_METERS, 0.001f)
    }

    @Test
    fun `confirmation parlee du mode`() {
        assertEquals("Crossing on.", CrossingText.mode(true))
        assertEquals("Crossing off.", CrossingText.mode(false))
        assertEquals("Crossing on.", CrossingText.mode(true, SpeechLanguage.En))
        assertEquals("Crossing off.", CrossingText.mode(false, SpeechLanguage.En))
    }
}
