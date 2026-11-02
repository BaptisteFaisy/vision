package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RouteManeuver
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTripNavTest {

    private fun maneuver(type: Int, begin: Int, street: String? = null) = RouteManeuver(
        type = type,
        instruction = "va",
        verbalInstruction = "",
        streetNames = listOfNotNull(street),
        lengthMeters = 10.0,
        timeSeconds = 8.0,
        beginShapeIndex = begin,
        endShapeIndex = begin,
    )

    private val leg = RouteLeg(
        shape = listOf(RoutePoint(48.8566, 2.3522), RoutePoint(48.8606, 2.3376)),
        maneuvers = listOf(maneuver(1, 0, "Rue de Rivoli"), maneuver(4, 1)),
        lengthMeters = 120.0,
        timeSeconds = 86.0,
    )

    @Test
    fun `depart et arrivee forces rue reprise destination nommee`() {
        val route = RouteTrip(listOf(leg), 120.0, 86.0).toNavRoute("la gare").getOrThrow()

        assertEquals(2, route.maneuvers.size)
        assertEquals(ManeuverAction.Start, route.maneuvers[0].action)
        assertEquals(ManeuverAction.Arrive, route.maneuvers[1].action)
        assertEquals(48.8566, route.maneuvers[0].lat, 1e-9)
        assertEquals("Rue de Rivoli", route.maneuvers[0].street)
        assertEquals("la gare", route.destinationName)
        assertEquals(leg.shape, route.shape)
    }

    @Test
    fun `sans nom la rue d'arrivee habille l'annonce`() {
        val withStreet = leg.copy(
            maneuvers = listOf(maneuver(1, 0, "Rue A"), maneuver(10, 1), maneuver(4, 1, "Rue B")),
        )

        val route = RouteTrip(listOf(withStreet), 120.0, 86.0).toNavRoute(null).getOrThrow()

        assertEquals(ManeuverAction.TurnRight, route.maneuvers[1].action)
        assertEquals("Rue B", route.destinationName)
    }

    @Test
    fun `passage protege du client devient traversee`() {
        val cross = maneuver(8, 1).copy(
            instruction = "Continuez sur le passage protégé.",
            verbalInstruction = "Continuez sur le passage protégé.",
        )
        val withCross = leg.copy(
            maneuvers = listOf(maneuver(1, 0, "Rue A"), cross, maneuver(4, 1, "Rue B")),
        )

        val route = RouteTrip(listOf(withCross), 120.0, 86.0).toNavRoute(null).getOrThrow()

        assertEquals(false, route.maneuvers[0].crossing)
        assertEquals(true, route.maneuvers[1].crossing)
        assertEquals(ManeuverAction.Continue, route.maneuvers[1].action)
        assertEquals(false, route.maneuvers[2].crossing)
    }

    @Test
    fun `rue en passage ne devient pas traversee`() {
        val homage = maneuver(10, 1, "Passage du Souvenir").copy(
            instruction = "À droite dans Passage du Souvenir.",
        )
        val withStreet = leg.copy(
            maneuvers = listOf(maneuver(1, 0, "Rue A"), homage, maneuver(4, 1, "Rue B")),
        )

        val route = RouteTrip(listOf(withStreet), 120.0, 86.0).toNavRoute(null).getOrThrow()

        assertEquals(false, route.maneuvers[1].crossing)
    }

    @Test
    fun `index hors trace refuse`() {
        val bad = leg.copy(maneuvers = listOf(maneuver(1, 0), maneuver(4, 9)))

        val failure = RouteTrip(listOf(bad), 120.0, 86.0).toNavRoute(null).exceptionOrNull()

        assertTrue(failure is RouteParseError.BadShapeIndex)
        assertEquals(1, (failure as RouteParseError.BadShapeIndex).index)
    }

    @Test
    fun `sans etape ni manoeuvres refuse`() {
        assertTrue(RouteTrip(emptyList(), 0.0, 0.0).toNavRoute(null).exceptionOrNull() is RouteParseError.MissingLeg)

        val lonely = leg.copy(maneuvers = listOf(maneuver(1, 0)))
        assertTrue(RouteTrip(listOf(lonely), 0.0, 0.0).toNavRoute(null).exceptionOrNull() is RouteParseError.EmptyManeuvers)
    }
}
