package dev.sam3vision.core.live

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaisonEnterCommandTest {

    @Test
    fun `maison ou house isole fait entrer`() {
        assertTrue(MaisonEnterCommand.isEnterOrder("maison"))
        assertTrue(MaisonEnterCommand.isEnterOrder("house"))
        assertTrue(MaisonEnterCommand.isEnterOrder("  MAISON ! "))
        assertTrue(MaisonEnterCommand.isEnterOrder("House."))
    }

    @Test
    fun `le duo mode maison fait entrer lui aussi`() {
        assertTrue(MaisonEnterCommand.isEnterOrder("mode maison"))
        assertTrue(MaisonEnterCommand.isEnterOrder("le mode maison"))
        assertTrue(MaisonEnterCommand.isEnterOrder("Active le mode maison !"))
    }

    @Test
    fun `les autres phrases ne declenchent pas, navigation et llm intacts`() {
        assertFalse(MaisonEnterCommand.isEnterOrder("va à la maison"))
        assertFalse(MaisonEnterCommand.isEnterOrder("je veux rentrer à la maison"))
        assertFalse(MaisonEnterCommand.isEnterOrder("la maison"))
        assertFalse(MaisonEnterCommand.isEnterOrder("où est la maison ?"))
        assertFalse(MaisonEnterCommand.isEnterOrder(""))
        assertFalse(MaisonEnterCommand.isEnterOrder("   "))
        assertFalse(MaisonEnterCommand.isEnterOrder("home"))
    }

    @Test
    fun `la confirmation parle la langue de la voix`() {
        assertEquals(
            "Mode maison activé. Je cherche les objets de l'appartement.",
            MaisonEnterText.entered(SpeechLanguage.Fr),
        )
        assertEquals(
            "Home mode on. Looking for household objects.",
            MaisonEnterText.entered(SpeechLanguage.En),
        )
        assertEquals(MaisonEnterText.entered(), MaisonEnterText.entered(SpeechLanguage.Fr))
    }
}
