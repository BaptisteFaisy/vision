package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Voix TwinLite de l'obstacle : textes restaurés, clips `tl_*`, clés séparées des mots SAM. */
class TwinLiteWordTest {

    @Test
    fun `textes francais de l obstacle`() {
        assertEquals("tout droit.", TwinLiteWord.Straight.text(SpeechLanguage.Fr))
        assertEquals("gauche.", TwinLiteWord.Left.text(SpeechLanguage.Fr))
        assertEquals("droite.", TwinLiteWord.Right.text(SpeechLanguage.Fr))
        assertEquals("obstacle, arrêtez-vous.", TwinLiteWord.Stop.text(SpeechLanguage.Fr))
    }

    @Test
    fun `textes anglais de l obstacle`() {
        assertEquals("straight ahead.", TwinLiteWord.Straight.text(SpeechLanguage.En))
        assertEquals("left.", TwinLiteWord.Left.text(SpeechLanguage.En))
        assertEquals("right.", TwinLiteWord.Right.text(SpeechLanguage.En))
        assertEquals("obstacle, stop.", TwinLiteWord.Stop.text(SpeechLanguage.En))
    }

    @Test
    fun `clips restaures tl`() {
        assertEquals("tl_straight", TwinLiteWord.Straight.clipId)
        assertEquals("tl_left", TwinLiteWord.Left.clipId)
        assertEquals("tl_right", TwinLiteWord.Right.clipId)
        assertEquals("tl_stop", TwinLiteWord.Stop.clipId)
    }

    @Test
    fun `cles separees des mots SAM, jamais de collision`() {
        TwinLiteWord.entries.forEach { tl ->
            GuidanceWord.entries.forEach { sam ->
                assertNotEquals("collision $tl / $sam", sam.key, tl.key)
            }
        }
    }

    @Test
    fun `depuis le mot SAM bouche, meme consigne`() {
        assertEquals(TwinLiteWord.Straight, TwinLiteWord.fromGuidance(GuidanceWord.Straight))
        assertEquals(TwinLiteWord.Left, TwinLiteWord.fromGuidance(GuidanceWord.Left))
        assertEquals(TwinLiteWord.Right, TwinLiteWord.fromGuidance(GuidanceWord.Right))
        assertEquals(TwinLiteWord.Stop, TwinLiteWord.fromGuidance(GuidanceWord.Stop))
    }

    @Test
    fun `clipIdFor retrouve chaque phrase`() {
        assertEquals("tl_straight", TwinLiteWord.clipIdFor("tout droit."))
        assertEquals("tl_left", TwinLiteWord.clipIdFor("gauche."))
        assertEquals("tl_right", TwinLiteWord.clipIdFor("droite."))
        assertEquals("tl_stop", TwinLiteWord.clipIdFor("obstacle, arrêtez-vous."))
        assertEquals("tl_stop", TwinLiteWord.clipIdFor("obstacle, stop."))
    }

    @Test
    fun `clipIdFor ignore les mots SAM et les positions`() {
        assertNull(TwinLiteWord.clipIdFor("Gauche."))
        assertNull(TwinLiteWord.clipIdFor("Stop."))
        assertNull(TwinLiteWord.clipIdFor("personne devant à 2 mètres."))
        assertNull(TwinLiteWord.clipIdFor(""))
    }
}
