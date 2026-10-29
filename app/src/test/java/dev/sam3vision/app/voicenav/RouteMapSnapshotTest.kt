package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavManeuver
import dev.sam3vision.core.nav.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapSnapshotTest {

    private val route = NavRoute(
        listOf(
            NavManeuver(ManeuverAction.Start, 48.86, 2.33),
            NavManeuver(ManeuverAction.TurnRight, 48.85, 2.34),
        ),
    )
    private val fix = NavFix(48.855, 2.335, 8f, 1_000L)
    private val signal = RoutePoint(48.852, 2.337)

    @Test
    fun `trace position et feux`() {
        val snap = routeMapSnapshotOf(route, fix, listOf(signal))!!

        assertEquals(2, snap.points.size)
        assertEquals(RoutePoint(48.855, 2.335), snap.position)
        assertEquals(8f, snap.accuracyM)
        assertEquals(listOf(signal), snap.signals)
    }

    @Test
    fun `fix inexploitable garde le trace sans position`() {
        val snap = routeMapSnapshotOf(route, NavFix(48.0, 2.0, 0f, 1_000L))!!

        assertEquals(2, snap.points.size)
        assertNull(snap.position)
        assertNull(snap.accuracyM)
    }

    @Test
    fun `sans trace ni position ni instantane`() {
        assertNull(routeMapSnapshotOf(null, fix))
        assertNull(routeMapSnapshotOf(NavRoute(emptyList()), fix))
        assertNull(routeMapSnapshotOf(NavRoute(listOf(NavManeuver(ManeuverAction.Start, Double.NaN, 0.0))), fix))
    }

    @Test
    fun `cle stable par itineraire`() {
        val same = NavRoute(
            listOf(
                NavManeuver(ManeuverAction.Start, 48.86, 2.33),
                NavManeuver(ManeuverAction.TurnLeft, 48.85, 2.34),
            ),
        )
        val other = NavRoute(listOf(NavManeuver(ManeuverAction.Start, 48.0, 2.0)))

        assertEquals(routeKeyOf(route), routeKeyOf(same))
        assertTrue(routeKeyOf(route) != routeKeyOf(other))
        assertEquals("none", routeKeyOf(null))
        assertEquals("none", routeKeyOf(NavRoute(emptyList())))
    }

    @Test
    fun `feux charges une fois echec retente apres delai`() {
        assertTrue(shouldFetchSignals(null, null, 1_000L))
        assertFalse(shouldFetchSignals(listOf(signal), null, 1_000L))
        assertFalse(shouldFetchSignals(emptyList(), 1_000L, 1_000L + SIGNALS_RETRY_MS - 1))
        assertTrue(shouldFetchSignals(emptyList(), 1_000L, 1_000L + SIGNALS_RETRY_MS))
    }
}
