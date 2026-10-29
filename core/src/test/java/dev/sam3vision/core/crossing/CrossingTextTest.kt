package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingTextTest {

    @Test
    fun `l annonce d un passage dit quoi faire, pas seulement ce qu on voit`() {
        // Une personne aveugle n'a que la voix : « passage piéton » seul ne lui
        // apprend pas qu'un mot suffit pour entrer dans le mode.
        assertEquals("Passage piéton. Dites traverser.", CrossingText.offer(SpeechLanguage.Fr))
        assertEquals("Crosswalk. Say cross for crossing mode.", CrossingText.offer(SpeechLanguage.En))
    }

    @Test
    fun `phrases francaises courtes`() {
        assertEquals("Rouge, attendez.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.Fr))
        assertEquals("Vert, traversez.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.Fr))
        assertEquals("Doute, attendez.", CrossingText.phrase(CrossingState.Unknown, SpeechLanguage.Fr))
    }

    @Test
    fun `phrases anglaises courtes`() {
        assertEquals("Red, wait.", CrossingText.phrase(CrossingState.Red, SpeechLanguage.En))
        assertEquals("Green, cross now.", CrossingText.phrase(CrossingState.Green, SpeechLanguage.En))
        assertEquals("Unsure, wait.", CrossingText.phrase(CrossingState.Unknown, SpeechLanguage.En))
    }

    @Test
    fun `le francais est le defaut des fonctions pures`() {
        assertEquals("Rouge, attendez.", CrossingText.phrase(CrossingState.Red))
    }

    @Test
    fun `la voix traversee passe par le TTS systeme`() {
        assertEquals(0.5f, CrossingText.VOICE_METERS, 0.001f)
    }

    @Test
    fun `confirmation parlee du mode`() {
        assertEquals("Mode traversée activé.", CrossingText.mode(true))
        assertEquals("Mode traversée coupé.", CrossingText.mode(false))
        assertEquals("Crossing mode on.", CrossingText.mode(true, SpeechLanguage.En))
        assertEquals("Crossing mode off.", CrossingText.mode(false, SpeechLanguage.En))
    }
}
