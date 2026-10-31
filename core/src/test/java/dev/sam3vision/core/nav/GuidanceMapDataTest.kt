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
    private val manoeuvres = listOf(depart, virage, arrivee)

    /** Tracé Valhalla : manœuvres reliées par leurs milieux. */
    private val shape = listOf(
        RoutePoint(48.8606, 2.3376),
        RoutePoint(48.85655, 2.3463),
        RoutePoint(48.8525, 2.3550),
        RoutePoint(48.8484, 2.3640),
        RoutePoint(48.8443, 2.3730),
    )
    private val route = NavRoute(
        maneuvers = manoeuvres,
        destinationName = "Gare de Lyon",
        shape = shape,
    )
    private val routeSansShape = NavRoute(manoeuvres, "Gare de Lyon")
    private val update = GuidanceUpdate(
        instruction = "Tournez a droite sur la rue de Rivoli",
        distanceToManeuverMeters = 120,
        remainingDistanceMeters = 900,
        remainingDurationSeconds = 600,
    )

    @Test
    fun `mapping complet trace shape position recalee camera suivi`() {
        // Fix pile sur un sommet du tracé : écart nul, position = fix.
        val fix = NavFix(lat = 48.85655, lon = 2.3463, accM = 8f, atMs = 1_000L)
        val state = guidanceMapStateOf(route, fix, update)
        val overlays = state.overlays

        assertEquals(shape, overlays.route)
        assertEquals(RoutePoint(48.8443, 2.3730), overlays.destination)
        assertEquals(48.85655, overlays.position!!.lat, 1e-9)
        assertEquals(2.3463, overlays.position!!.lon, 1e-9)
        assertEquals(8f, overlays.accuracyM)
        assertEquals(listOf(RoutePoint(48.8525, 2.3550)), overlays.crossings)
        assertEquals(3, overlays.maneuvers.size)
        assertEquals("Start", overlays.maneuvers[0].label)
        assertEquals("Turn right on rue de Rivoli", overlays.maneuvers[1].label)
        assertEquals("Arrived — Gare de Lyon", overlays.maneuvers[2].label)
        assertTrue(overlays.maneuvers.all { !it.upcoming })

        // Caméra de suivi : centrée sur la position (sur le tracé),
        // zoom rue minimum (le cadre bouge avec l'utilisateur).

        val camera = state.camera!!
        assertEquals(48.85655, camera.target.lat, 1e-9)
        assertEquals(2.3463, camera.target.lon, 1e-9)
        assertEquals(16.0, camera.zoom, 0.0)
        assertEquals(0f, camera.bearingDeg, 0f)
    }

    @Test
    fun `camera suit la position hors trace`() {
        // Écarté à plus de 30 m : la position brute reste affichée, et la
        // caméra la suit (le tracé peut sortir du cadre, comme en suivi).
        val fix = NavFix(lat = 48.8700, lon = 2.3900, accM = 8f, atMs = 1_000L)
        val state = guidanceMapStateOf(route, fix, update)
        assertEquals(RoutePoint(48.8700, 2.3900), state.overlays.position)
        val camera = state.camera!!
        assertEquals(48.8700, camera.target.lat, 1e-9)
        assertEquals(2.3900, camera.target.lon, 1e-9)
        assertEquals(0f, camera.bearingDeg, 0f)
        assertEquals(16.0, camera.zoom, 0.0)
    }

    @Test
    fun `sans shape le trace replie sur les manoeuvres`() {
        val state = guidanceMapStateOf(routeSansShape, null, update)
        val overlays = state.overlays

        val points = manoeuvres.map { RoutePoint(it.lat, it.lon) }
        assertEquals(points, overlays.route)
        assertEquals(RoutePoint(48.8443, 2.3730), overlays.destination)
        assertNull(overlays.position)
        // Sans position : cadrage du tracé.
        val camera = state.camera!!
        assertEquals((48.8606 + 48.8443) / 2.0, camera.target.lat, 1e-9)
        assertEquals((2.3376 + 2.3730) / 2.0, camera.target.lon, 1e-9)
        assertTrue(camera.zoom in 2.0..19.0)
    }

    @Test
    fun `trace ignore shape trop courte ou route nulle`() {
        assertTrue(routePointsOf(null).isEmpty())
        val courte = routeSansShape.copy(shape = listOf(RoutePoint(48.85, 2.35)))
        assertEquals(
            manoeuvres.map { RoutePoint(it.lat, it.lon) },
            routePointsOf(courte),
        )
        val sale = routeSansShape.copy(
            shape = listOf(RoutePoint(Double.NaN, 2.35), RoutePoint(48.85, 2.35)),
        )
        assertEquals(
            manoeuvres.map { RoutePoint(it.lat, it.lon) },
            routePointsOf(sale),
        )
    }

    @Test
    fun `fix proche recale fix lointain brut fix mort nul`() {
        // Droite plein sud à lon 2,34 : la projection garde lat, plaque lon.
        val droite = listOf(
            RoutePoint(48.8600, 2.3400),
            RoutePoint(48.8580, 2.3400),
            RoutePoint(48.8560, 2.3400),
        )
        val proche = NavFix(48.8580, 2.3401, 8f, 1_000L)
        val recale = snappedPositionOf(proche, droite)!!
        assertEquals(48.8580, recale.lat, 1e-9)
        assertEquals(2.3400, recale.lon, 1e-9)

        val loin = NavFix(48.8580, 2.3414, 8f, 1_000L)
        assertEquals(RoutePoint(48.8580, 2.3414), snappedPositionOf(loin, droite))

        assertNull(snappedPositionOf(null, droite))
        assertNull(snappedPositionOf(NavFix(Double.NaN, 2.34, 8f, 1_000L), droite))
        assertNull(snappedPositionOf(NavFix(48.8580, 2.34, 0f, 1_000L), droite))
    }

    @Test
    fun `hors guidage route nulle ou vide`() {
        for (candidate in listOf(null, NavRoute(emptyList()))) {
            val state = guidanceMapStateOf(candidate, null, update)
            assertNull(state.camera)
            assertTrue(state.overlays.route.isEmpty())
            assertNull(state.overlays.destination)
            assertNull(state.overlays.position)
            assertNull(state.overlays.accuracyM)
            assertTrue(state.overlays.crossings.isEmpty())
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
            val state = guidanceMapStateOf(routeSansShape, candidate, update)
            assertNull(state.overlays.position)
            assertNull(state.overlays.accuracyM)
            // Cible = centre du trace, nord en haut, zoom de cadrage.
            val camera = state.camera!!
            assertEquals((48.8606 + 48.8443) / 2.0, camera.target.lat, 1e-9)
            assertEquals((2.3376 + 2.3730) / 2.0, camera.target.lon, 1e-9)
            assertEquals(0f, camera.bearingDeg, 0f)
            assertTrue(camera.zoom in 2.0..19.0)
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
        val state = guidanceMapStateOf(bancale, null, null)
        val overlays = state.overlays

        assertEquals(listOf(RoutePoint(48.8443, 2.3730)), overlays.route)
        assertEquals(RoutePoint(48.8443, 2.3730), overlays.destination)
        assertEquals(listOf(RoutePoint(48.8443, 2.3730)), overlays.crossings)
        assertEquals(1, overlays.maneuvers.size)
        assertFalse(overlays.maneuvers[0].upcoming)
        // Un seul point : zoom rue et cible sur le trace.
        val camera = state.camera!!
        assertEquals(RoutePoint(48.8443, 2.3730), camera.target)
        assertEquals(16.0, camera.zoom, 0.0)
    }

    @Test
    fun `camera nulle sans position ni point`() {
        val bancale = NavRoute(
            listOf(
                NavManeuver(ManeuverAction.Start, Double.NaN, 2.34),
                NavManeuver(ManeuverAction.Arrive, 48.84, Double.NaN),
            ),
        )
        val state = guidanceMapStateOf(bancale, null, null)
        assertNull(state.camera)
        assertTrue(state.overlays.route.isEmpty())
        assertNull(state.overlays.destination)
        assertTrue(state.overlays.maneuvers.isEmpty())
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
        val zoomProche = guidanceMapStateOf(proche, null, null).camera!!.zoom
        val zoomLoin = guidanceMapStateOf(loin, null, null).camera!!.zoom
        assertTrue(zoomProche > zoomLoin)
    }
}
