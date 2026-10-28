package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.net.RouteClient
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaRouteLoaderTest {

    private val place = GeocodedPlace(48.8606, 2.3376, "Gare de Lyon, Paris", 0.9f)
    private val origin = RoutePoint(48.8566, 2.3522)
    private val trip = RouteTrip(listOf(RouteLeg(emptyList(), emptyList(), 830.0, 610.0)), 830.0, 610.0)

    private fun loader(
        geocode: suspend (String) -> Result<GeocodedPlace> = { Result.success(place) },
        originNow: () -> RoutePoint? = { origin },
        route: suspend (RoutePoint, RoutePoint) -> Result<RouteTrip> = { _, _ -> Result.success(trip) },
        baseUrl: () -> String = { "https://route.test" },
        routeTimeoutMs: Long = 15_000,
    ) = ValhallaRouteLoader(
        geocode = geocode,
        originNow = originNow,
        routeClient = {
            object : RouteClient {
                override suspend fun route(from: RoutePoint, to: RoutePoint, costing: String) = route(from, to)
                override suspend fun health() = Result.success(true)
            }
        },
        baseUrl = baseUrl,
        routeTimeoutMs = routeTimeoutMs,
    )

    @Test
    fun `adresse et itineraire donnent le resume complet`() = runBlocking {
        var asked: Pair<RoutePoint, RoutePoint>? = null
        val full = loader(route = { from, to -> asked = from to to; Result.success(trip) })

        val summary = full("la gare").getOrThrow()

        assertEquals("la gare", summary.destination)
        assertEquals("Gare de Lyon, Paris", summary.address)
        assertEquals(830L, summary.distanceMeters)
        assertEquals(610L, summary.durationSeconds)
        assertEquals(origin, asked?.first)
        assertEquals(RoutePoint(48.8606, 2.3376), asked?.second)
    }

    @Test
    fun `sans position ni url l'adresse seule reste affichable`() = runBlocking {
        val noOrigin = loader(originNow = { null })("la gare").getOrThrow()
        assertEquals(0L, noOrigin.distanceMeters)
        assertEquals("Gare de Lyon, Paris", noOrigin.address)

        val noUrl = loader(baseUrl = { "  " })("la gare").getOrThrow()
        assertEquals(0L, noUrl.durationSeconds)
    }

    @Test
    fun `geocodage en echec remonte son motif`() = runBlocking {
        val failure = loader(geocode = { Result.failure(IllegalStateException("adresse introuvable")) })
            .invoke("nulle part").exceptionOrNull()

        assertEquals("adresse introuvable", failure?.message)
    }

    @Test
    fun `un collaborateur qui jette devient un echec`() = runBlocking {
        val throwing = loader(geocode = { throw IllegalStateException("panne") })

        assertTrue(throwing("la gare").isFailure)
    }

    @Test
    fun `echec route isole repris une fois puis resume`() = runBlocking {
        var attempts = 0
        val flaky = loader(
            route = { _, _ ->
                attempts++
                if (attempts == 1) Result.failure(IOException("connexion perdue")) else Result.success(trip)
            },
        )

        val summary = flaky("la gare").getOrThrow()

        assertEquals(830L, summary.distanceMeters)
        assertEquals(2, attempts)
    }

    @Test
    fun `route qui jette puis reussit donne le resume`() = runBlocking {
        var attempts = 0
        val throwing = loader(
            route = { _, _ ->
                attempts++
                if (attempts == 1) throw IOException("panne") else Result.success(trip)
            },
        )

        assertTrue(throwing("la gare").isSuccess)
        assertEquals(2, attempts)
    }

    @Test
    fun `double echec route donne un motif francais`() = runBlocking {
        var attempts = 0
        val down = loader(
            route = { _, _ ->
                attempts++
                Result.failure(IOException("serveur injoignable"))
            },
        )

        val failure = down("la gare").exceptionOrNull()

        assertEquals(2, attempts)
        assertEquals("itinéraire indisponible (serveur injoignable)", failure?.message)
    }

    @Test
    fun `route trop lente echoue sur delai borne`() = runBlocking {
        var attempts = 0
        val slow = loader(
            route = { _, _ ->
                attempts++
                delay(30_000)
                Result.success(trip)
            },
            routeTimeoutMs = 50,
        )

        val failure = slow("la gare").exceptionOrNull()

        assertEquals(2, attempts)
        assertEquals("itinéraire indisponible (délai dépassé)", failure?.message)
    }
}
