package dev.sam3vision.core.net

import dev.sam3vision.core.TestHttpServer
import dev.sam3vision.core.model.RoutePoint
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteClientTest {

    private val depart = RoutePoint(48.8566, 2.3522)
    private val arrivee = RoutePoint(48.8606, 2.3376)

    /** Réponse Valhalla réelle (Paris, piéton) : `shape` précision 6 vérifié à l'octet. */
    private val validTrip = """
        {"trip":{"legs":[{"maneuvers":[
          {"type":1,"instruction":"Marchez vers le nord.","verbal_pre_transition_instruction":"Marchez vers le nord sur la rue de Rivoli.","street_names":["Rue de Rivoli"],"length":0.12,"time":86.0,"begin_shape_index":0,"end_shape_index":1},
          {"type":4,"instruction":"Arrivé.","street_names":[],"length":0.0,"time":0.0,"begin_shape_index":1,"end_shape_index":1}
        ],"shape":"o`~d|AocqnC_yFno[","summary":{"length":0.12,"time":86.0}}],
        "summary":{"length":0.12,"time":86.0},"status_message":"Found route between points","status":0,"units":"kilometers","language":"en-US"}}
    """.trimIndent()

    private var tripBody = validTrip
    private var routeCode = 200

    private val server = TestHttpServer { request ->
        when {
            request.path == "/route" && request.method == "POST" ->
                TestHttpServer.Response(routeCode, if (routeCode == 200) tripBody else """{"error_code":106,"error":"No route","status_code":400,"status":"Bad Request"}""")
            else -> TestHttpServer.Response(404, "{}")
        }
    }

    @After
    fun tearDown() = server.close()

    @Test
    fun `route envoie les deux points en pieton anglais avec un User-Agent propre`() = runBlocking {
        HttpRouteClient(server.baseUrl).route(depart, arrivee).getOrThrow()

        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/route", request.path)
        assertEquals(ROUTE_USER_AGENT, request.headers["user-agent"])
        assertTrue(!request.headers.containsKey("authorization"))
        val sent = JSONObject(request.body)
        assertEquals(ROUTE_COSTING_PEDESTRIAN, sent.getString("costing"))
        assertEquals("en-US", sent.getJSONObject("directions_options").getString("language"))
        val locations = sent.getJSONArray("locations")
        assertEquals(2, locations.length())
        assertEquals(48.8566, locations.getJSONObject(0).getDouble("lat"), 1e-9)
        assertEquals(2.3522, locations.getJSONObject(0).getDouble("lon"), 1e-9)
        assertEquals(48.8606, locations.getJSONObject(1).getDouble("lat"), 1e-9)
        assertEquals(2.3376, locations.getJSONObject(1).getDouble("lon"), 1e-9)
    }

    @Test
    fun `route parse le trace les manoeuvres et les totaux`() = runBlocking {
        val trip = HttpRouteClient(server.baseUrl).route(depart, arrivee).getOrThrow()

        assertEquals(1, trip.legs.size)
        assertEquals(120.0, trip.lengthMeters, 1e-6)
        assertEquals(86.0, trip.timeSeconds, 1e-9)
        val leg = trip.legs.single()
        assertEquals(120.0, leg.lengthMeters, 1e-6)
        assertEquals(86.0, leg.timeSeconds, 1e-9)
        assertEquals(2, leg.shape.size)
        listOf(depart, arrivee).zip(leg.shape).forEach { (attendu, lu) ->
            assertEquals(attendu.lat, lu.lat, 1e-9)
            assertEquals(attendu.lon, lu.lon, 1e-9)
        }
        assertEquals(2, leg.maneuvers.size)
        val first = leg.maneuvers[0]
        assertEquals(1, first.type)
        assertEquals("Marchez vers le nord.", first.instruction)
        assertEquals("Marchez vers le nord sur la rue de Rivoli.", first.verbalInstruction)
        assertEquals(listOf("Rue de Rivoli"), first.streetNames)
        assertEquals(120.0, first.lengthMeters, 1e-6)
        assertEquals(86.0, first.timeSeconds, 1e-9)
        assertEquals(0, first.beginShapeIndex)
        assertEquals(1, first.endShapeIndex)
        assertEquals(trip.shape, leg.shape)
        assertEquals(trip.maneuvers, leg.maneuvers)
    }

    @Test
    fun `route sans consigne parlee garde une instruction vide`() = runBlocking {
        val trip = HttpRouteClient(server.baseUrl).route(depart, arrivee).getOrThrow()

        val last = trip.maneuvers.last()
        assertEquals(4, last.type)
        assertEquals("", last.verbalInstruction)
        assertTrue(last.streetNames.isEmpty())
    }

    @Test
    fun `route 400 donne une ApiError Http typée`() {
        routeCode = 400
        val failure = runBlocking { HttpRouteClient(server.baseUrl).route(depart, arrivee) }.exceptionOrNull()
        assertTrue("attendu Http 400, obtenu $failure", failure is ApiError.Http && failure.code == 400)
    }

    @Test
    fun `route sans troncon est une reponse inattendue`() {
        tripBody = """{"trip":{"legs":[],"summary":{"length":0.0,"time":0.0}},"status":0}"""
        val failure = runBlocking { HttpRouteClient(server.baseUrl).route(depart, arrivee) }.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `route sans trip est une reponse inattendue`() {
        tripBody = """{"units":"kilometers"}"""
        val failure = runBlocking { HttpRouteClient(server.baseUrl).route(depart, arrivee) }.exceptionOrNull()
        assertTrue("attendu BadResponse, obtenu $failure", failure is ApiError.BadResponse)
    }

    @Test
    fun `route malformee donne BadResponse`() {
        val broken = TestHttpServer { TestHttpServer.Response(200, "pas du json") }
        try {
            val failure = runBlocking { HttpRouteClient(broken.baseUrl).route(depart, arrivee) }.exceptionOrNull()
            assertTrue(failure is ApiError.BadResponse)
        } finally {
            broken.close()
        }
    }

    @Test
    fun `route delai depasse donne ApiError Timeout`() {
        val slow = TestHttpServer { TestHttpServer.Response(200, validTrip, delayMs = 2_000) }
        val impatient = defaultHttpClient(ROUTE_TIMEOUT_SECONDS).newBuilder()
            .callTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()
        try {
            val failure = runBlocking { HttpRouteClient(slow.baseUrl, impatient).route(depart, arrivee) }
                .exceptionOrNull()
            assertNotNull(failure)
            assertTrue("attendu Timeout, obtenu $failure", failure is ApiError.Timeout)
        } finally {
            slow.close()
        }
    }

    @Test
    fun `le delai de l itineraire est bien de quinze secondes en pieton`() {
        assertEquals(15L, ROUTE_TIMEOUT_SECONDS)
        assertEquals("pedestrian", ROUTE_COSTING_PEDESTRIAN)
    }
}
