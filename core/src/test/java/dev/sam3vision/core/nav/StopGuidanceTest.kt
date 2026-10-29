package dev.sam3vision.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopGuidanceTest {

    @Test
    fun `ordres d'arret reconnus`() {
        assertTrue(StopGuidanceParser.isStop("arrête le guidage"))
        assertTrue(StopGuidanceParser.isStop("stop la navigation"))
        assertTrue(StopGuidanceParser.isStop("annule l'itinéraire"))
        assertTrue(StopGuidanceParser.isStop("c'est bon, arrête le guidage s'il te plaît"))
    }

    @Test
    fun `casse et accents indifferents`() {
        assertTrue(StopGuidanceParser.isStop("ARRÊTE LE GUIDAGE"))
        assertTrue(StopGuidanceParser.isStop("arrete le guidage"))
        assertTrue(StopGuidanceParser.isStop("Stoppe La Navigation"))
        assertTrue(StopGuidanceParser.isStop("annule l'itineraire"))
    }

    @Test
    fun `verbe seul ne coupe pas`() {
        assertFalse(StopGuidanceParser.isStop("arrête !"))
        assertFalse(StopGuidanceParser.isStop("stop"))
        assertFalse(StopGuidanceParser.isStop("annule"))
    }

    @Test
    fun `phrases sans ordre ignorees`() {
        assertFalse(StopGuidanceParser.isStop(""))
        assertFalse(StopGuidanceParser.isStop("   "))
        assertFalse(StopGuidanceParser.isStop("emmène-moi à la gare"))
        assertFalse(StopGuidanceParser.isStop("le guidage est précis"))
        assertFalse(StopGuidanceParser.isStop("quelle heure est-il ?"))
    }

    @Test
    fun `desactivation reconnue`() {
        assertTrue(StopGuidanceParser.isDisable("désactive le GPS"))
        assertTrue(StopGuidanceParser.isDisable("désactive le guidage"))
        assertTrue(StopGuidanceParser.isDisable("désactives la navigation"))
        assertTrue(StopGuidanceParser.isDisable("coupe le GPS"))
        assertTrue(StopGuidanceParser.isDisable("coupe le guidage"))
        assertTrue(StopGuidanceParser.isDisable("éteins le GPS"))
        assertTrue(StopGuidanceParser.isDisable("arrête et désactive le GPS s'il te plaît"))
        assertTrue(StopGuidanceParser.isDisable("ça m'énerve, désactives le GPS"))
    }

    @Test
    fun `activation reconnue`() {
        assertTrue(StopGuidanceParser.isEnable("active le GPS"))
        assertTrue(StopGuidanceParser.isEnable("active le guidage"))
        assertTrue(StopGuidanceParser.isEnable("réactive le GPS"))
        assertTrue(StopGuidanceParser.isEnable("rallume la navigation"))
        assertTrue(StopGuidanceParser.isEnable("ACTIVE LE GPS"))
    }

    @Test
    fun `desactivation sans objet ignoree`() {
        assertFalse(StopGuidanceParser.isDisable("désactive"))
        assertFalse(StopGuidanceParser.isDisable("désactiver le compte"))
        assertFalse(StopGuidanceParser.isDisable(""))
        assertFalse(StopGuidanceParser.isDisable("   "))
        assertFalse(StopGuidanceParser.isDisable("désactives la sourdine"))
    }

    @Test
    fun `activation sans objet ignoree`() {
        assertFalse(StopGuidanceParser.isEnable("active"))
        assertFalse(StopGuidanceParser.isEnable(""))
        assertFalse(StopGuidanceParser.isEnable("   "))
        assertFalse(StopGuidanceParser.isEnable("actives la lampe"))
    }

    @Test
    fun `arret simple n'est pas un ordre gps`() {
        assertFalse(StopGuidanceParser.isDisable("arrête le guidage"))
        assertFalse(StopGuidanceParser.isEnable("arrête le guidage"))
    }

    @Test
    fun `desactivation combinee l'emporte sur l arret`() {
        // « arrête et désactive » : l'ordre le plus fort est honoré (les deux
        // verbes sont présents, la fonction doit être éteinte).
        assertTrue(StopGuidanceParser.isDisable("arrête et désactive le GPS s'il te plaît"))
        assertFalse(StopGuidanceParser.isStop("désactive le GPS"))
        assertFalse(StopGuidanceParser.isStop("réactive le GPS"))
    }

    @Test
    fun `desactivation et activation excluent l'autre`() {
        assertFalse(StopGuidanceParser.isDisable("active le GPS"))
        assertFalse(StopGuidanceParser.isEnable("désactive le GPS"))
        assertFalse(StopGuidanceParser.isDisable("réactive le GPS"))
    }
}
