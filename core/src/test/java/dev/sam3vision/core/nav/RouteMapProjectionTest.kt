package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteMapProjectionTest {

    private val louvre = RoutePoint(48.8606, 2.3376)
    private val gare = RoutePoint(48.8443, 2.3730)

    @Test
    fun `cadre englobant ignore les points non finis`() {
        val bounds = mapBoundsOf(
            listOf(
                louvre,
                gare,
                RoutePoint(Double.NaN, 2.0),
                RoutePoint(48.0, Double.POSITIVE_INFINITY),
            ),
        )!!

        assertEquals(48.8443, bounds.minLat, 1e-9)
        assertEquals(48.8606, bounds.maxLat, 1e-9)
        assertEquals(2.3376, bounds.minLon, 1e-9)
        assertEquals(2.3730, bounds.maxLon, 1e-9)
    }

    @Test
    fun `sans point fini pas de cadre`() {
        assertNull(mapBoundsOf(emptyList()))
        assertNull(mapBoundsOf(listOf(RoutePoint(Double.NaN, Double.NaN))))
    }

    @Test
    fun `nord en haut et contenu dans le canevas`() {
        val fit = mapBoundsOf(listOf(louvre, gare))!!.fit(1000f, 1000f, 100f)
        val north = fit.project(louvre)
        val south = fit.project(gare)

        // Nord (lat max) plus haut que sud, les deux dans le canevas paddé.
        assertTrue(north.y < south.y)
        for (point in listOf(north, south)) {
            assertTrue(point.x in 100f..900f)
            assertTrue(point.y in 100f..900f)
        }
    }

    @Test
    fun `point seul tombe au centre`() {
        val fit = mapBoundsOf(listOf(louvre, louvre))!!.fit(1000f, 800f, 50f)
        val center = fit.project(louvre)

        assertEquals(500f, center.x, 1e-3f)
        assertEquals(400f, center.y, 1e-3f)
    }

    @Test
    fun `point non fini tombe au centre`() {
        val fit = mapBoundsOf(listOf(louvre, gare))!!.fit(1000f, 800f, 50f)
        val center = fit.project(RoutePoint(Double.NaN, 0.0))

        assertEquals(500f, center.x, 1e-3f)
        assertEquals(400f, center.y, 1e-3f)
    }

    @Test
    fun `cercle de précision proportionnel et positif`() {
        val fit = mapBoundsOf(listOf(louvre, gare))!!.fit(1000f, 800f, 50f)

        assertEquals(0f, fit.metersToPx(-5.0), 1e-6f)
        assertTrue(fit.metersToPx(10.0) > 0f)
        assertEquals(fit.metersToPx(10.0) * 2f, fit.metersToPx(20.0), 1e-3f)
    }
}
