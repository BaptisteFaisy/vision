package dev.sam3vision.core.picto

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictoPlaceModeTest {

    @Test
    fun `le lieu par defaut est dehors, la rue parle au lancement`() {
        assertEquals(PictoPlaceMode.DEHORS, PictoPlaceMode.DEFAULT)
        assertTrue(PictoPlaceMode.DEDANS.pictoEnabled)
        assertFalse(PictoPlaceMode.DEHORS.pictoEnabled)
    }

    @Test
    fun `deux lieux exclusifs, ni plus ni moins`() {
        assertEquals(setOf(PictoPlaceMode.DEDANS, PictoPlaceMode.DEHORS), PictoPlaceMode.entries.toSet())
    }

    @Test
    fun `parse comprend les ordres francais dedans et dehors`() {
        assertEquals(PictoPlaceMode.DEDANS, PlaceModeOrder.parse("mode dedans"))
        assertEquals(PictoPlaceMode.DEDANS, PlaceModeOrder.parse("Passe en mode intérieur !"))
        assertEquals(PictoPlaceMode.DEHORS, PlaceModeOrder.parse("mode dehors"))
        assertEquals(PictoPlaceMode.DEHORS, PlaceModeOrder.parse("mets le mode extérieur"))
    }

    @Test
    fun `parse comprend indoor mode et outdoor mode`() {
        assertEquals(PictoPlaceMode.DEDANS, PlaceModeOrder.parse("indoor mode"))
        assertEquals(PictoPlaceMode.DEHORS, PlaceModeOrder.parse("outdoor mode"))
        assertEquals(PictoPlaceMode.DEHORS, PlaceModeOrder.parse("Switch to outdoor mode!"))
    }

    @Test
    fun `parse refuse tout ce qui n est pas un ordre explicite`() {
        assertNull(PlaceModeOrder.parse("dedans"))
        assertNull(PlaceModeOrder.parse("je suis dehors"))
        assertNull(PlaceModeOrder.parse("indoor"))
        assertNull(PlaceModeOrder.parse("mode maison"))
        assertNull(PlaceModeOrder.parse("mode dedans dehors"))
        assertNull(PlaceModeOrder.parse(""))
    }

    @Test
    fun `la bascule se confirme dans la langue de la voix`() {
        assertEquals("Mode dedans activé.", PlaceModeText.switched(PictoPlaceMode.DEDANS, SpeechLanguage.Fr))
        assertEquals("Mode dehors activé.", PlaceModeText.switched(PictoPlaceMode.DEHORS, SpeechLanguage.Fr))
        assertEquals("Indoor mode on.", PlaceModeText.switched(PictoPlaceMode.DEDANS, SpeechLanguage.En))
        assertEquals("Outdoor mode on.", PlaceModeText.switched(PictoPlaceMode.DEHORS, SpeechLanguage.En))
    }
}
