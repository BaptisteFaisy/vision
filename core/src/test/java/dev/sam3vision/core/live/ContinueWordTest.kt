package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Rappels de suivi du chemin : textes, cache, consigne d'origine. */
class ContinueWordTest {

    @Test
    fun `textes anglais`() {
        assertEquals("Continue straight.", ContinueWord.Straight.text(SpeechLanguage.En))
        assertEquals("Continue left.", ContinueWord.Left.text(SpeechLanguage.En))
        assertEquals("Continue right.", ContinueWord.Right.text(SpeechLanguage.En))
    }

    @Test
    fun `textes francais`() {
        assertEquals("Continue tout droit.", ContinueWord.Straight.text(SpeechLanguage.Fr))
        assertEquals("Continue à gauche.", ContinueWord.Left.text(SpeechLanguage.Fr))
        assertEquals("Continue à droite.", ContinueWord.Right.text(SpeechLanguage.Fr))
    }

    @Test
    fun `entrees cache`() {
        assertEquals("guide_continue_straight", ContinueWord.Straight.clipId)
        assertEquals("guide_continue_left", ContinueWord.Left.clipId)
        assertEquals("guide_continue_right", ContinueWord.Right.clipId)
    }

    @Test
    fun `rappel de la consigne stable`() {
        assertEquals(ContinueWord.Straight, ContinueWord.fromGuidance(GuidanceWord.Straight))
        assertEquals(ContinueWord.Left, ContinueWord.fromGuidance(GuidanceWord.Left))
        assertEquals(ContinueWord.Right, ContinueWord.fromGuidance(GuidanceWord.Right))
    }

    @Test
    fun `stop sans rappel`() {
        assertNull(ContinueWord.fromGuidance(GuidanceWord.Stop))
    }

    @Test
    fun `entree cache d une phrase parlee`() {
        assertEquals("guide_continue_straight", ContinueWord.clipIdFor("Continue straight."))
        assertEquals("guide_continue_straight", ContinueWord.clipIdFor("Continue tout droit."))
        assertEquals("guide_continue_left", ContinueWord.clipIdFor("Continue left."))
        assertEquals("guide_continue_left", ContinueWord.clipIdFor("Continue à gauche."))
        assertEquals("guide_continue_right", ContinueWord.clipIdFor("Continue right."))
        assertEquals("guide_continue_right", ContinueWord.clipIdFor("Continue à droite."))
    }

    @Test
    fun `phrase inconnue sans entree cache`() {
        assertNull(ContinueWord.clipIdFor("Straight."))
        assertNull(ContinueWord.clipIdFor("left."))
        assertNull(ContinueWord.clipIdFor("Personne à 1 mètre."))
    }
}
