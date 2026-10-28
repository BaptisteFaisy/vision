package dev.sam3vision.core.navcore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verrouille le détecteur d'ordre STOP ([NavStopParser]) : formes « arrête /
 * stop / annule (le/la) guidage / navigation », insensibles à la casse et à
 * l'accent — sans avaler les ordres GO ni les questions.
 */
class NavStopTest {

    @Test
    fun `arrete le guidage est un stop`() {
        assertTrue(NavStopParser.isStop("arrête le guidage"))
        assertTrue(NavStopParser.isStop("arrete le guidage"))
        assertTrue(NavStopParser.isStop("Arrête le guidage !"))
    }

    @Test
    fun `stop et annule la navigation sont des stops`() {
        assertTrue(NavStopParser.isStop("stop la navigation"))
        assertTrue(NavStopParser.isStop("STOPPE le guidage"))
        assertTrue(NavStopParser.isStop("annule la navigation"))
        assertTrue(NavStopParser.isStop("s'il te plaît, annule le guidage"))
    }

    @Test
    fun `le vide n est pas un stop`() {
        assertFalse(NavStopParser.isStop(""))
        assertFalse(NavStopParser.isStop("   "))
    }

    @Test
    fun `un ordre GO n est pas un stop`() {
        assertFalse(NavStopParser.isStop("je veux aller à la gare de Lyon"))
        assertFalse(NavStopParser.isStop("naviguer vers Paris"))
        assertFalse(NavStopParser.isStop("emmène-moi à Nice"))
    }

    @Test
    fun `une question n est pas un stop`() {
        assertFalse(NavStopParser.isStop("où est la pharmacie la plus proche ?"))
        assertFalse(NavStopParser.isStop("c'est loin la tour Eiffel ?"))
    }

    @Test
    fun `stop seul sans complement n est pas un stop`() {
        assertFalse(NavStopParser.isStop("stop"))
        assertFalse(NavStopParser.isStop("arrête"))
    }
}
