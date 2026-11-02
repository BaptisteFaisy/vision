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
        assertEquals("Start.", maneuverShort(1))
        assertEquals("Arrived.", maneuverShort(4))
        assertEquals("Straight on.", maneuverShort(8))
        assertEquals("Turn right.", maneuverShort(10))
        assertEquals("Turn left.", maneuverShort(15))
        assertEquals("Bear right.", maneuverShort(9))
        assertEquals("Bear left.", maneuverShort(16))
        assertEquals("Make a U-turn.", maneuverShort(12))
        assertEquals("Roundabout.", maneuverShort(26))
        assertEquals("Exit.", maneuverShort(27))
    }

    @Test
    fun `un type inconnu ou de transport retombe sur continuer`() {
        assertEquals("Continue.", maneuverShort(0))
        assertEquals("Continue.", maneuverShort(30))
        assertEquals("Continue.", maneuverShort(99))
    }
}
