package dev.sam3vision.app.voicenav

import dev.sam3vision.core.model.RouteLeg
import dev.sam3vision.core.model.RouteManeuver
import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.model.RouteTrip
import dev.sam3vision.core.nav.GeocodedPlace
import dev.sam3vision.core.nav.NavRoute
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
        geocode: suspend (String, RoutePoint?) -> Result<GeocodedPlace> = { _, _ -> Result.success(place) },
        originNow: suspend () -> RoutePoint? = { origin },
        route: suspend (RoutePoint, RoutePoint) -> Result<RouteTrip> = { _, _ -> Result.success(trip) },
        baseUrl: () -> String = { "https://route.test" },
        routeTimeoutMs: Long = 15_000,
        onPreview: (NavRoute?) -> Unit = {},
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
        onPreview = onPreview,
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
    fun `sans position ni url le motif est explicite, jamais des zeros`() = runBlocking {
        val noOrigin = loader(originNow = { null })("la gare").exceptionOrNull()
        assertEquals("starting position unavailable (enable location)", noOrigin?.message)

        val noUrl = loader(baseUrl = { "  " })("la gare").exceptionOrNull()
        assertEquals("route service not configured", noUrl?.message)
    }

    @Test
    fun `geocodage en echec remonte son motif`() = runBlocking {
        val failure = loader(geocode = { _, _ -> Result.failure(IllegalStateException("adresse introuvable")) })
            .invoke("nulle part").exceptionOrNull()

        assertEquals("adresse introuvable", failure?.message)
    }

    @Test
    fun `un collaborateur qui jette devient un echec`() = runBlocking {
        val throwing = loader(geocode = { _, _ -> throw IllegalStateException("panne") })

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
    fun `double echec route donne un motif anglais`() = runBlocking {
        var attempts = 0
        val down = loader(
            route = { _, _ ->
                attempts++
                Result.failure(IOException("serveur injoignable"))
            },
        )

        val failure = down("la gare").exceptionOrNull()

        assertEquals(2, attempts)
        assertEquals("route unavailable (serveur injoignable)", failure?.message)
    }

    @Test
    fun `l'origine est transmise au geocodage pour le tri par proximite`() = runBlocking {
        var askedNear: RoutePoint? = null
        val full = loader(geocode = { _, near -> askedNear = near; Result.success(place) })

        full("sacré-cœur").getOrThrow()

        assertEquals(origin, askedNear)
    }

    @Test
    fun `le plafond distance valhalla devient un motif friendly`() = runBlocking {
        val body = "{\"Detail\":\"Path distance exceeds the max distance limit: 25000 meters\"}"
        val down = loader(route = { _, _ -> Result.failure(IOException("erreur HTTP 500 : $body")) })

        val failure = down("loin").exceptionOrNull()

        assertEquals("route unavailable (destination too far on foot)", failure?.message)
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
        assertEquals("route unavailable (timeout)", failure?.message)
    }

    @Test
    fun `apercu publie null au lancement puis l'itineraire au succes`() = runBlocking {
        val shape = listOf(origin, RoutePoint(place.latitude, place.longitude))
        val leg = RouteLeg(
            shape,
            listOf(
                RouteManeuver(1, "Partez", "", listOf("Rue de départ"), 400.0, 300.0, 0, 1),
                RouteManeuver(4, "Arrivée", "", listOf("Rue d'arrivée"), 430.0, 310.0, 1, 1),
            ),
            830.0,
            610.0,
        )
        val previews = mutableListOf<NavRoute?>()
        val full = loader(
            route = { _, _ -> Result.success(trip.copy(legs = listOf(leg))) },
            onPreview = { previews.add(it) },
        )

        val summary = full("la gare").getOrThrow()

        assertEquals("la gare", summary.destination)
        assertEquals(2, previews.size)
        assertEquals(null, previews.first())
        val preview = previews.last()!!
        assertEquals(2, preview.maneuvers.size)
        assertEquals("la gare", preview.destinationName)
        assertEquals(shape, preview.shape)
    }

    @Test
    fun `apercu reste null quand la route echoue`() = runBlocking {
        val previews = mutableListOf<NavRoute?>()
        val down = loader(
            route = { _, _ -> Result.failure(IOException("serveur injoignable")) },
            onPreview = { previews.add(it) },
        )

        assertTrue(down("la gare").isFailure)

        assertEquals(listOf(null), previews)
    }

    @Test
    fun `origine suspendue attendue avant le resume`() = runBlocking {
        var asked: Pair<RoutePoint, RoutePoint>? = null
        val waited = loader(
            originNow = { delay(50); origin },
            route = { from, to -> asked = from to to; Result.success(trip) },
        )

        val summary = waited("la gare").getOrThrow()

        assertEquals(830L, summary.distanceMeters)
        assertEquals(origin, asked?.first)
    }
}
