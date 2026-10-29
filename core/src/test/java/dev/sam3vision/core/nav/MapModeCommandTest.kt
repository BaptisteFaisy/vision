package dev.sam3vision.core.nav

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordre vocal du mode navigation : ce qui entre, ce qui n'entre pas, et ce
 * qui sort. Les cas « destination » et « ordre de sortie » sont les gardes
 * contre le vol d'une phrase par le mode.
 */
class MapModeCommandTest {

    @Test
    fun `les mots seuls entrent`() {
        assertTrue(MapModeCommand.isEnterOrder("navigation"))
        assertTrue(MapModeCommand.isEnterOrder("Carte"))
        assertTrue(MapModeCommand.isEnterOrder("le plan ?"))
    }

    @Test
    fun `mode plus carte entre`() {
        assertTrue(MapModeCommand.isEnterOrder("mode navigation"))
        assertTrue(MapModeCommand.isEnterOrder("mode carte"))
        assertTrue(MapModeCommand.isEnterOrder("passe en mode navigation"))
    }

    @Test
    fun `verbe d'ouverture puis la carte entre`() {
        assertTrue(MapModeCommand.isEnterOrder("ouvre la carte"))
        assertTrue(MapModeCommand.isEnterOrder("Ouvre le plan."))
        assertTrue(MapModeCommand.isEnterOrder("affiche la navigation"))
        assertTrue(MapModeCommand.isEnterOrder("montre-moi la carte"))
    }

    @Test
    fun `ou suis je entre`() {
        assertTrue(MapModeCommand.isEnterOrder("où suis-je ?"))
        assertTrue(MapModeCommand.isEnterOrder("Where am I?"))
    }

    @Test
    fun `une destination reste une navigation`() {
        assertFalse(MapModeCommand.isEnterOrder("navigation vers la gare"))
        assertFalse(MapModeCommand.isEnterOrder("ouvre la carte de la gare de Lyon"))
        assertFalse(MapModeCommand.isEnterOrder("va à la gare"))
        assertFalse(MapModeCommand.isEnterOrder("je veux aller à la carte postale"))
    }

    @Test
    fun `les ordres de sortie n'entrent pas`() {
        assertTrue(MapModeCommand.isLeaveOrder("arrête la navigation"))
        assertTrue(MapModeCommand.isLeaveOrder("quitte la carte"))
        assertTrue(MapModeCommand.isLeaveOrder("ferme le plan"))
        assertFalse(MapModeCommand.isEnterOrder("arrête la navigation"))
        assertFalse(MapModeCommand.isEnterOrder("quitte la carte"))
    }

    @Test
    fun `arreter le guidage n'est pas une sortie de carte`() {
        assertFalse(MapModeCommand.isLeaveOrder("arrête le guidage"))
        assertFalse(MapModeCommand.isLeaveOrder("stop"))
    }

    @Test
    fun `une question reste une question`() {
        assertFalse(MapModeCommand.isEnterOrder("où est la pharmacie la plus proche ?"))
        assertFalse(MapModeCommand.isEnterOrder("quelle est cette carte ?"))
        assertFalse(MapModeCommand.isEnterOrder("   "))
        assertFalse(MapModeCommand.isLeaveOrder("   "))
    }

    @Test
    fun `les phrases du mode sont dites dans la langue de la voix`() {
        assertEquals(
            "Mode navigation. La carte est à l'écran.",
            MapModeText.entered(SpeechLanguage.Fr),
        )
        assertEquals(
            "Navigation mode. The map is on screen.",
            MapModeText.entered(SpeechLanguage.En),
        )
        assertEquals("Mode navigation fermé.", MapModeText.left(SpeechLanguage.Fr))
        assertEquals("Navigation mode closed.", MapModeText.left(SpeechLanguage.En))
    }
}
