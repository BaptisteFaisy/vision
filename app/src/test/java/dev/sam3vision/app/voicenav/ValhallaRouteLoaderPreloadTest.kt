package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.net.RouteClient
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Canal latéral résumé → moteur ([ValhallaRouteLoader.lastLoad]) : exposé, frais, invalidé. */
class ValhallaRouteLoaderPreloadTest {

    private val place = GeocodedPlace(48.8606, 2.3376, "Gare de Lyon, Paris", 0.9f)
    private val origin = RoutePoint(48.8566, 2.3522)
    private val trip = RouteTrip(listOf(RouteLeg(emptyList(), emptyList(), 830.0, 610.0)), 830.0, 610.0)
    private var now = 1_000_000L

    private fun loader(
        route: suspend (RoutePoint, RoutePoint) -> Result<RouteTrip> = { _, _ -> Result.success(trip) },
    ) = ValhallaRouteLoader(
        geocode = { _, _ -> Result.success(place) },
        originNow = { origin },
        routeClient = {
            object : RouteClient {
                override suspend fun route(from: RoutePoint, to: RoutePoint, costing: String) = route(from, to)
                override suspend fun health() = Result.success(true)
            }
        },
        baseUrl = { "https://route.test" },
        nowMs = { now },
    )

    @Test
    fun `rien avant la premiere demande`() {
        assertNull(loader().lastLoad)
    }

    @Test
    fun `le succes expose origine destination et trip pour le moteur`() = runBlocking {
        val full = loader()

        full("  Gare de Lyon. ").getOrThrow()

        val preloaded = full.lastLoad!!
        assertEquals("Gare de Lyon", preloaded.destination)
        assertEquals(origin, preloaded.origin)
        assertEquals(RoutePoint(48.8606, 2.3376), preloaded.dest)
        assertEquals("Gare de Lyon, Paris", preloaded.destLabel)
        assertEquals(trip, preloaded.trip)
        assertTrue(preloaded.isReusableFor("Gare de Lyon", now))
    }

    @Test
    fun `nouvelle demande invalide le chargement precedent`() = runBlocking {
        val full = loader()

        full("gare de lyon").getOrThrow()
        full("tour eiffel").getOrThrow()

        assertEquals("tour eiffel", full.lastLoad!!.destination)
    }

    @Test
    fun `echec route ne laisse aucun chargement`() = runBlocking {
        val full = loader()
        full("gare de lyon").getOrThrow()

        val down = loader(route = { _, _ -> Result.failure(IOException("serveur injoignable")) })
        assertTrue(down("gare de lyon").isFailure)

        assertNull(down.lastLoad)
        assertFalse(full.lastLoad!!.isReusableFor("gare de lyon", now + 61_000L))
    }
}
