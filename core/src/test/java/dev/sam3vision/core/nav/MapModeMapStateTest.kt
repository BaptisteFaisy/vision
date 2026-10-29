package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * État carte du mode navigation : ce que la carte OSM affiche sans
 * destination — position, précision, feux — et le repli sans position.
 */
class MapModeMapStateTest {

    private val fix = NavFix(lat = 48.8606, lon = 2.3376, accM = 8f, atMs = 0L)
    private val feu = RoutePoint(48.8610, 2.3380)

    @Test
    fun `avec un fix la camera est sur la position au zoom rue`() {
        val state = mapModeMapStateOf(fix, listOf(feu))

        assertEquals(48.8606, state.camera!!.target.lat, 1e-9)
        assertEquals(2.3376, state.camera!!.target.lon, 1e-9)
        assertTrue("zoom rue attendu", state.camera!!.zoom > 14.0)
        assertEquals(0f, state.camera!!.bearingDeg, 0f)
    }

    @Test
    fun `la position porte sa precision et les feux sont dessines`() {
        val state = mapModeMapStateOf(fix, listOf(feu, RoutePoint(Double.NaN, 2.0)))

        assertEquals(RoutePoint(48.8606, 2.3376), state.overlays.position)
        assertEquals(8f, state.overlays.accuracyM!!, 0f)
        assertEquals(listOf(feu), state.overlays.signals)
        assertTrue("aucun itinéraire dans ce mode", state.overlays.route.isEmpty())
        assertNull(state.overlays.destination)
    }

    @Test
    fun `sans fix la camera est nulle et rien n'est dessine`() {
        val state = mapModeMapStateOf(null, listOf(feu))

        assertNull(state.camera)
        assertNull(state.overlays.position)
        assertNull(state.overlays.accuracyM)
        assertEquals(listOf(feu), state.overlays.signals)
    }

    @Test
    fun `un fix inutilisable ne devient pas une position`() {
        val state = mapModeMapStateOf(NavFix(Double.NaN, 2.0, 8f, 0L), emptyList())

        assertNull(state.camera)
        assertNull(state.overlays.position)
    }
}
