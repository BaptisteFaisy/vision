package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaShapeTest {

    @Test
    fun `le trace parisien se decode au metre pres`() {
        val points = decodeValhallaShape("o`~d|AocqnC_yFno[")

        assertEquals(2, points.size)
        assertEquals(48.8566, points[0].lat, 1e-6)
        assertEquals(2.3522, points[0].lon, 1e-6)
        assertEquals(48.8606, points[1].lat, 1e-6)
        assertEquals(2.3376, points[1].lon, 1e-6)
    }

    @Test
    fun `un trace vide donne aucun point`() {
        assertTrue(decodeValhallaShape("").isEmpty())
    }

    @Test
    fun `un pas nul donne l origine`() {
        assertEquals(listOf(RoutePoint(0.0, 0.0)), decodeValhallaShape("??"))
    }

    @Test
    fun `un trace coupe garde les points deja decodes`() {
        // Trois pas complets puis un pas tronqué : le premier point survit.
        assertEquals(listOf(RoutePoint(0.0, 0.0)), decodeValhallaShape("???_"))
    }

    @Test
    fun `les libelles parles couvrent le guidage pieton`() {
        assertEquals("Départ.", maneuverShortFr(1))
        assertEquals("Arrivée.", maneuverShortFr(4))
        assertEquals("Continuez tout droit.", maneuverShortFr(8))
        assertEquals("Tournez à droite.", maneuverShortFr(10))
        assertEquals("Tournez à gauche.", maneuverShortFr(15))
        assertEquals("Légèrement à droite.", maneuverShortFr(9))
        assertEquals("Légèrement à gauche.", maneuverShortFr(16))
        assertEquals("Faites demi-tour.", maneuverShortFr(12))
        assertEquals("Entrez dans le rond-point.", maneuverShortFr(26))
        assertEquals("Sortez du rond-point.", maneuverShortFr(27))
    }

    @Test
    fun `un type inconnu ou de transport retombe sur continuer`() {
        assertEquals("Continuez.", maneuverShortFr(0))
        assertEquals("Continuez.", maneuverShortFr(30))
        assertEquals("Continuez.", maneuverShortFr(99))
    }
}
