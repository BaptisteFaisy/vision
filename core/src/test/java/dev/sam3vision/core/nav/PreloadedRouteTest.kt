package dev.sam3vision.core.nav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreloadedRouteTest {

    private val trip = RouteTrip(listOf(RouteLeg(emptyList(), emptyList(), 830.0, 610.0)), 830.0, 610.0)
    private val origin = RoutePoint(48.8566, 2.3522)
    private val dest = RoutePoint(48.8606, 2.3376)

    private fun preloaded(destination: String = "Gare de Lyon", loadedAtMs: Long = 1_000_000L) =
        PreloadedRoute(destination, origin, dest, "Gare de Lyon, Paris", trip, loadedAtMs)

    @Test
    fun `meme destination fraiche est reutilisable`() {
        assertTrue(preloaded().isReusableFor("Gare de Lyon", 1_000_000L))
        assertTrue(preloaded().isReusableFor("Gare de Lyon", 1_059_999L))
    }

    @Test
    fun `la comparaison normalise la demande`() {
        assertTrue(preloaded().isReusableFor("  Gare de Lyon. ", 1_000_000L))
    }

    @Test
    fun `destination differente refusee`() {
        assertFalse(preloaded().isReusableFor("tour eiffel", 1_000_000L))
        assertFalse(preloaded().isReusableFor("   ", 1_000_000L))
    }

    @Test
    fun `perime apres 60 s ou dans le futur`() {
        assertFalse(preloaded().isReusableFor("Gare de Lyon", 1_060_001L))
        assertFalse(preloaded().isReusableFor("Gare de Lyon", 999_999L))
    }
}
