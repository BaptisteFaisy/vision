package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingTextTest {

    @Test
    fun `phrases francaises courtes, rouge vert`() {
        assertEquals("Rouge, attendez.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.Fr))
        assertEquals("Vert, traversez.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.Fr))
    }

    @Test
    fun `phrases anglaises courtes, binaire rouge vert`() {
        assertEquals("Don't cross yet", CrossingText.phrase(CrossingState.Red, SpeechLanguage.En))
        assertEquals("You can cross", CrossingText.phrase(CrossingState.Green, SpeechLanguage.En))
    }

    @Test
    fun `le clignotant dit ne pas s'engager`() {
        assertEquals("Vert clignotant, ne pas s'engager.", CrossingText.blinking(SpeechLanguage.Fr))
        assertEquals("Flashing green, do not cross.", CrossingText.blinking(SpeechLanguage.En))
        assertEquals("Flashing green, do not cross.", CrossingText.blinking())
    }

    @Test
    fun `le pret dit pret, pas traversez`() {
        assertEquals("Prêt.", CrossingText.ready(SpeechLanguage.Fr))
        assertEquals("Ready.", CrossingText.ready(SpeechLanguage.En))
        assertEquals("Ready.", CrossingText.ready())
    }

    @Test
    fun `la contradiction dit feux contradictoires, attendez`() {
        assertEquals("Feux contradictoires, attendez.", CrossingText.contradiction(SpeechLanguage.Fr))
        assertEquals("Conflicting lights, wait.", CrossingText.contradiction(SpeechLanguage.En))
        assertEquals("Conflicting lights, wait.", CrossingText.contradiction())
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
        assertEquals("Crossing mode on.", CrossingText.mode(true))
        assertEquals("Crossing mode off.", CrossingText.mode(false))
        assertEquals("Crossing mode on.", CrossingText.mode(true, SpeechLanguage.En))
        assertEquals("Crossing mode off.", CrossingText.mode(false, SpeechLanguage.En))
    }
}
