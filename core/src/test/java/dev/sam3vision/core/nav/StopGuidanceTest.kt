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
}
