package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceMapDataTest {

    private val depart = NavManeuver(ManeuverAction.Start, 48.8606, 2.3376)
    private val virage = NavManeuver(
        ManeuverAction.TurnRight, 48.8525, 2.3550,
        street = "rue de Rivoli", crossing = true,
    )
    private val arrivee = NavManeuver(ManeuverAction.Arrive, 48.8443, 2.3730)
    private val route = NavRoute(
        maneuvers = listOf(depart, virage, arrivee),
        destinationName = "Gare de Lyon",
    )
    private val fix = NavFix(lat = 48.8580, lon = 2.3400, accM = 8f, atMs = 1_000L)
    private val update = GuidanceUpdate(
        instruction = "Tournez a droite sur la rue de Rivoli",
        distanceToManeuverMeters = 120,
        remainingDistanceMeters = 900,
        remainingDurationSeconds = 600,
    )

    @Test
    fun `mapping complet route fix signaux`() {
        val feux = listOf(RoutePoint(48.8530, 2.3560), RoutePoint(48.8450, 2.3720))
        val state = guidanceMapStateOf(route, fix, update, feux)
        val overlays = state.overlays

        assertEquals(3, overlays.route.size)
        assertEquals(RoutePoint(48.8443, 2.3730), overlays.destination)
        assertEquals(RoutePoint(48.8580, 2.3400), overlays.position)
        assertEquals(8f, overlays.accuracyM)
        assertEquals(listOf(RoutePoint(48.8525, 2.3550)), overlays.crossings)
        assertEquals(feux, overlays.signals)
        assertEquals(3, overlays.maneuvers.size)
        assertEquals("Départ", overlays.maneuvers[0].label)
        assertEquals("Tournez à droite sur rue de Rivoli", overlays.maneuvers[1].label)
        assertEquals("Arrivée — Gare de Lyon", overlays.maneuvers[2].label)
        assertTrue(overlays.maneuvers.all { !it.upcoming })

        val camera = state.camera!!
        assertEquals(RoutePoint(48.8580, 2.3400), camera.target)
        assertEquals(0f, camera.bearingDeg, 0f)
        assertTrue(camera.zoom in 2.0..19.0)
    }

    @Test
    fun `hors guidage route nulle ou vide`() {
        for (candidate in listOf(null, NavRoute(emptyList()))) {
            val state = guidanceMapStateOf(candidate, fix, update, listOf(RoutePoint(48.0, 2.0)))
            assertNull(state.camera)
            assertTrue(state.overlays.route.isEmpty())
            assertNull(state.overlays.destination)
            assertNull(state.overlays.position)
            assertNull(state.overlays.accuracyM)
            assertTrue(state.overlays.crossings.isEmpty())
            assertTrue(state.overlays.signals.isEmpty())
            assertTrue(state.overlays.maneuvers.isEmpty())
        }
    }

    @Test
    fun `fix inexploitable sans position`() {
        val mauvais = listOf(
            NavFix(Double.NaN, 2.3400, 8f, 1_000L),
            NavFix(48.8580, 2.3400, 0f, 1_000L),
            NavFix(48.8580, 2.3400, Float.NaN, 1_000L),
        )
        for (candidate in mauvais) {
            val state = guidanceMapStateOf(route, candidate, update, emptyList())
            assertNull(state.overlays.position)
            assertNull(state.overlays.accuracyM)
            // Cible = centre du trace, nord en haut.
            val camera = state.camera!!
            assertEquals((48.8606 + 48.8443) / 2.0, camera.target.lat, 1e-9)
            assertEquals((2.3376 + 2.3730) / 2.0, camera.target.lon, 1e-9)
            assertEquals(0f, camera.bearingDeg, 0f)
        }
    }

    @Test
    fun `listes filtrees sans point fini`() {
        val bancale = NavRoute(
            listOf(
                NavManeuver(ManeuverAction.Start, Double.NaN, Double.NaN),
                NavManeuver(ManeuverAction.Arrive, 48.8443, 2.3730, crossing = true),
            ),
        )
        val state = guidanceMapStateOf(
            bancale, null, null,
            listOf(RoutePoint(48.8450, 2.3720), RoutePoint(Double.NaN, 1.0)),
        )
        val overlays = state.overlays

        assertEquals(listOf(RoutePoint(48.8443, 2.3730)), overlays.route)
        assertEquals(RoutePoint(48.8443, 2.3730), overlays.destination)
        assertEquals(listOf(RoutePoint(48.8443, 2.3730)), overlays.crossings)
        assertEquals(listOf(RoutePoint(48.8450, 2.3720)), overlays.signals)
        assertEquals(1, overlays.maneuvers.size)
        assertFalse(overlays.maneuvers[0].upcoming)
        // Un seul point : zoom rue et cible sur le trace.
        val camera = state.camera!!
        assertEquals(RoutePoint(48.8443, 2.3730), camera.target)
        assertEquals(16.0, camera.zoom, 0.0)
    }

    @Test
    fun `zoom resserre quand le trace grandit`() {
        val proche = NavRoute(
            listOf(
                NavManeuver(ManeuverAction.Start, 48.8606, 2.3376),
                NavManeuver(ManeuverAction.Arrive, 48.8600, 2.3380),
            ),
        )
        val loin = NavRoute(
            listOf(
                NavManeuver(ManeuverAction.Start, 48.8606, 2.3376),
                NavManeuver(ManeuverAction.Arrive, 48.8443, 2.3730),
            ),
        )
        val zoomProche = guidanceMapStateOf(proche, null, null, emptyList()).camera!!.zoom
        val zoomLoin = guidanceMapStateOf(loin, null, null, emptyList()).camera!!.zoom
        assertTrue(zoomProche > zoomLoin)
    }
}
