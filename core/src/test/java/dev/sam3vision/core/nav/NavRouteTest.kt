package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Modèle d'itinéraire (JUnit seul, pur JVM) : [NavRoute.validate] accepte
 * départ + arrivée coordonnés et refuse le reste par [RouteError] typé ;
 * [NavFix.isUsable] trie les fixes exploitables.
 */
class NavRouteTest {

    private fun maneuver(
        action: ManeuverAction,
        lat: Double = 48.85,
        lon: Double = 2.35,
    ) = NavManeuver(action = action, lat = lat, lon = lon)

    private fun valid() = NavRoute(
        maneuvers = listOf(
            maneuver(ManeuverAction.Start, 48.8530, 2.3499),
            maneuver(ManeuverAction.TurnLeft, 48.8550, 2.3450),
            maneuver(ManeuverAction.Arrive, 48.8584, 2.2945),
        ),
        destinationName = "tour Eiffel",
    )

    @Test
    fun `itineraire complet valide`() {
        val result = NavRoute.validate(valid())
        assertTrue(result.isSuccess)
        assertEquals("tour Eiffel", result.getOrNull()?.destinationName)
    }

    @Test
    fun `moins de deux manoeuvres refuse`() {
        val route = NavRoute(maneuvers = listOf(maneuver(ManeuverAction.Start)))
        val error = NavRoute.validate(route).exceptionOrNull()
        assertTrue(error is RouteError.TooFewManeuvers)
    }

    @Test
    fun `sans depart refuse`() {
        val route = valid().copy(
            maneuvers = listOf(
                maneuver(ManeuverAction.Continue),
                maneuver(ManeuverAction.Arrive),
            ),
        )
        assertTrue(NavRoute.validate(route).exceptionOrNull() is RouteError.MissingStart)
    }

    @Test
    fun `sans arrivee refuse`() {
        val route = valid().copy(
            maneuvers = listOf(
                maneuver(ManeuverAction.Start),
                maneuver(ManeuverAction.Continue),
            ),
        )
        assertTrue(NavRoute.validate(route).exceptionOrNull() is RouteError.MissingArrival)
    }

    @Test
    fun `point non fini refuse avec son index`() {
        val route = valid().copy(
            maneuvers = listOf(
                maneuver(ManeuverAction.Start),
                maneuver(ManeuverAction.TurnLeft, Double.NaN, 2.3450),
                maneuver(ManeuverAction.Arrive),
            ),
        )
        val error = NavRoute.validate(route).exceptionOrNull()
        assertTrue(error is RouteError.BadPoint)
        assertEquals(1, (error as RouteError.BadPoint).index)
    }

    @Test
    fun `fix exploitable`() {
        assertTrue(NavFix(48.85, 2.35, 8f, 1_000L).isUsable())
        assertFalse(NavFix(Double.NaN, 2.35, 8f, 1_000L).isUsable())
        assertFalse(NavFix(48.85, 2.35, 0f, 1_000L).isUsable())
        assertFalse(NavFix(48.85, 2.35, Float.NaN, 1_000L).isUsable())
    }
}
