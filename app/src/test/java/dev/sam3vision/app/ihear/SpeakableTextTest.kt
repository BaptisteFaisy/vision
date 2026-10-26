package dev.sam3vision.app.ihear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Régression : une transcription qui n'est qu'un marqueur audio (« [silence] »,
 * « [outro jingle] ») ne doit pas partir en synthèse — ElevenLabs retire les marqueurs
 * et répondait 400 « Input at position 0 has empty text » (mesuré sur le téléphone).
 */
class SpeakableTextTest {

    @Test
    fun `un marqueur audio seul ne se dit pas`() {
        assertFalse(speakableText("[silence]"))
        assertFalse(speakableText("[outro jingle]"))
        assertFalse(speakableText("  [background noise]  "))
        assertFalse(speakableText("[a][b]"))
    }

    @Test
    fun `un vrai texte se dit, marqueurs compris`() {
        assertTrue(speakableText("Allô, allô, test"))
        assertTrue(speakableText("[music] ouvre la porte"))
        assertTrue(speakableText("12"))
    }

    @Test
    fun `un texte vide ou sans lettre ne se dit pas`() {
        assertFalse(speakableText(""))
        assertFalse(speakableText("   "))
        assertFalse(speakableText(" — … "))
    }
}
