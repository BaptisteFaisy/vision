package dev.sam3vision.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Détection des traversées (JUnit seul, pur JVM) : Valhalla ne type pas
 * les passages protégés (type 8, sans rue) — seul le texte compte.
 */
class CrossingManeuverTest {

    @Test
    fun `passage protege Valhalla detecte`() {
        assertTrue(CrossingManeuver.matches("Continuez sur le passage protégé.", ""))
        assertTrue(CrossingManeuver.matches("", "Continuez sur le passage protégé."))
    }

    @Test
    fun `casse et accents neutralises`() {
        assertTrue(CrossingManeuver.matches("Continuez sur le PASSAGE PROTÉGÉ.", ""))
    }

    @Test
    fun `rue en passage ne declenche pas`() {
        assertFalse(
            CrossingManeuver.matches(
                "Tournez à droite dans Passage du Souvenir.",
                "Tournez à droite dans Passage du Souvenir.",
            ),
        )
    }

    @Test
    fun `consignes ordinaires ignorees`() {
        assertFalse(CrossingManeuver.matches("Tournez à droite dans l'allée.", ""))
        assertFalse(CrossingManeuver.matches("Marchez vers l'est.", "Marchez vers l'est."))
        assertFalse(CrossingManeuver.matches("", ""))
    }
}
