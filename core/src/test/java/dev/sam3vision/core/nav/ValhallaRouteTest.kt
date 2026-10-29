package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapte Valhalla (JUnit seul, pur JVM) : un `trip` jouet à 3 manœuvres
 * (tracé polyligne6 réel) devient un [NavRoute] guidable ; réponses
 * cassées ⇒ [RouteParseError] typé, jamais d'exception.
 */
class ValhallaRouteTest {

    private fun trip(shape: String, maneuvers: String): String =
        """{"trip":{"legs":[{"shape":"$shape","maneuvers":[$maneuvers]}]}}"""

    private fun maneuver(type: Int, index: Int, street: String): String =
        """{"type":$type,"begin_shape_index":$index,"street_names":["$street"]}"""

    /** Tracé réel : (48.85, 2.35) → (48.8509, 2.35) → (48.8509, 2.35135). */
    private val shape = "_dqd|A_zlnCgw@??ksA"

    private fun validTrip(): String = trip(
        shape,
        listOf(
            maneuver(1, 0, "Rue du depart"),
            maneuver(10, 1, "rue de la Paix"),
            maneuver(4, 2, "Avenue d'arrivee"),
        ).joinToString(","),
    )

    @Test
    fun `trip valide devient itineraire guidable`() {
        val route = ValhallaRoute.parse(validTrip(), "la gare").getOrThrow()
        assertTrue(NavRoute.validate(route).isSuccess)
        assertEquals(3, route.maneuvers.size)
        assertEquals(ManeuverAction.Start, route.maneuvers[0].action)
        assertEquals(ManeuverAction.TurnRight, route.maneuvers[1].action)
        assertEquals(ManeuverAction.Arrive, route.maneuvers[2].action)
        assertEquals("rue de la Paix", route.maneuvers[1].street)
        assertEquals(48.8509, route.maneuvers[1].lat, 1e-5)
        assertEquals(2.35135, route.maneuvers[2].lon, 1e-5)
        assertEquals("la gare", route.destinationName)
        assertEquals(3, route.shape.size)
        assertEquals(48.85, route.shape[0].lat, 1e-5)
        assertEquals(2.35, route.shape[0].lon, 1e-5)
        assertEquals(48.8509, route.shape[1].lat, 1e-5)
        assertEquals(2.35, route.shape[1].lon, 1e-5)
        assertEquals(48.8509, route.shape[2].lat, 1e-5)
        assertEquals(2.35135, route.shape[2].lon, 1e-5)
    }

    @Test
    fun `destination reprise de la derniere rue sans parametre`() {
        val route = ValhallaRoute.parse(validTrip()).getOrThrow()
        assertEquals("Avenue d'arrivee", route.destinationName)
    }

    @Test
    fun `json illisible refuse`() {
        val error = ValhallaRoute.parse("ceci n'est pas du json").exceptionOrNull()
        assertTrue(error is RouteParseError.InvalidJson)
    }

    @Test
    fun `sans etape refuse`() {
        val error = ValhallaRoute.parse("""{"trip":{}}""").exceptionOrNull()
        assertTrue(error is RouteParseError.MissingLeg)
    }

    @Test
    fun `trace illisible refuse`() {
        val error = ValhallaRoute.parse(
            trip("!!", maneuver(1, 0, "Rue") + "," + maneuver(4, 0, "Rue")),
        ).exceptionOrNull()
        assertTrue(error is RouteParseError.BadShape)
    }

    @Test
    fun `index hors trace refuse avec son rang`() {
        val error = ValhallaRoute.parse(
            trip(shape, maneuver(1, 0, "Rue") + "," + maneuver(4, 9, "Rue")),
        ).exceptionOrNull()
        assertTrue(error is RouteParseError.BadShapeIndex)
        assertEquals(1, (error as RouteParseError.BadShapeIndex).index)
    }

    @Test
    fun `passage protege sans rue devient traversee`() {
        val json = trip(
            shape,
            listOf(
                maneuver(1, 0, "Rue du depart"),
                """{"type":8,"begin_shape_index":1,"instruction":"Continuez sur le passage protégé.","verbal_pre_transition_instruction":"Continuez sur le passage protégé."}""",
                maneuver(4, 2, "Avenue d'arrivee"),
            ).joinToString(","),
        )

        val route = ValhallaRoute.parse(json).getOrThrow()

        assertEquals(false, route.maneuvers[0].crossing)
        assertEquals(true, route.maneuvers[1].crossing)
        assertEquals(null, route.maneuvers[1].street)
        assertEquals(false, route.maneuvers[2].crossing)
    }

    @Test
    fun `type inconnu replie sur tout droit`() {
        assertEquals(ManeuverAction.Continue, ValhallaRoute.mapType(99))
        assertEquals(ManeuverAction.UTurn, ValhallaRoute.mapType(12))
        assertEquals(ManeuverAction.Roundabout, ValhallaRoute.mapType(26))
        assertEquals(ManeuverAction.SlightLeft, ValhallaRoute.mapType(16))
    }
}
