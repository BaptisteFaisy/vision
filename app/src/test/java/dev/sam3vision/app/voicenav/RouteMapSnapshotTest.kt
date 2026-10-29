package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavManeuver
import dev.sam3vision.core.nav.NavRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RouteMapSnapshotTest {

    private val shape = listOf(
        RoutePoint(48.86, 2.33),
        RoutePoint(48.855, 2.335),
        RoutePoint(48.85, 2.34),
    )
    private val route = NavRoute(
        listOf(
            NavManeuver(ManeuverAction.Start, 48.86, 2.33),
            NavManeuver(ManeuverAction.TurnRight, 48.85, 2.34),
        ),
        shape = shape,
    )
    private val fix = NavFix(48.855, 2.335, 8f, 1_000L)

    @Test
    fun `trace shape et position recalee sur la shape`() {
        val snap = routeMapSnapshotOf(route, fix)!!

        assertEquals(shape, snap.points)
        assertNotNull(snap.position)
        // Fix pile sur un sommet : le recalé retombe dessus (delta flottant).
        val position = snap.position!!
        assertEquals(48.855, position.lat, 1e-9)
        assertEquals(2.335, position.lon, 1e-9)
        assertEquals(8f, snap.accuracyM)
    }

    @Test
    fun `fix inexploitable garde le trace sans position`() {
        val snap = routeMapSnapshotOf(route, NavFix(48.0, 2.0, 0f, 1_000L))!!

        assertEquals(shape, snap.points)
        assertNull(snap.position)
        assertNull(snap.accuracyM)
    }

    @Test
    fun `sans trace ni position ni instantane`() {
        assertNull(routeMapSnapshotOf(null, fix))
        assertNull(routeMapSnapshotOf(NavRoute(emptyList()), fix))
        assertNull(routeMapSnapshotOf(NavRoute(listOf(NavManeuver(ManeuverAction.Start, Double.NaN, 0.0))), fix))
    }
}
