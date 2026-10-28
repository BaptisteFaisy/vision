package dev.sam3vision.core.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaRouteClientTest {

    @Test
    fun `le corps route porte les deux points et le cout pieton`() {
        val body = JSONObject(buildRouteRequestJson(LatLon(48.8566, 2.3522), LatLon(48.8606, 2.3376), COSTING_PEDESTRIAN))

        assertEquals(COSTING_PEDESTRIAN, body.getString("costing"))
        val points = body.getJSONArray("locations")
        assertEquals(2, points.length())
        assertEquals(48.8566, points.getJSONObject(0).getDouble("lat"), 1e-6)
        assertEquals(2.3376, points.getJSONObject(1).getDouble("lon"), 1e-6)
    }

    @Test
    fun `le cout par defaut est pieton`() {
        assertEquals("pedestrian", COSTING_PEDESTRIAN)
    }

    @Test
    fun `le resume et les etapes sont lus en metres et secondes`() {
        val trip = parseRouteDigest(TRIP_JSON).getOrThrow()

        assertEquals(830.0, trip.distanceMeters, 0.5)
        assertEquals(610.0, trip.timeSeconds, 0.5)
        assertEquals(2, trip.steps.size)
        assertEquals("Continuez tout droit sur Rue de Rivoli.", trip.steps[0].instruction)
        assertEquals(100.0, trip.steps[0].distanceMeters, 0.5)
        assertEquals("Vous êtes arrivé à destination.", trip.steps[1].instruction)
    }

    @Test
    fun `un corps sans itineraire echoue en BadResponse`() {
        val result = parseRouteDigest("""{"erreur":"pas d'itineraire"}""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is ApiError.BadResponse)
    }

    @Test
    fun `un corps illisible echoue au lieu de lever`() {
        val result = parseRouteDigest("ceci n'est pas du JSON")

        assertTrue(result.isFailure)
    }

    @Test
    fun `les champs absents valent zero sans faire echouer`() {
        val trip = parseRouteDigest("""{"trip":{"summary":{},"legs":[{"maneuvers":[{}]}]}}""").getOrThrow()

        assertEquals(0.0, trip.distanceMeters, 0.001)
        assertEquals(1, trip.steps.size)
        assertEquals("", trip.steps[0].instruction)
    }

    companion object {
        private const val TRIP_JSON = """{"trip":{"summary":{"length":0.83,"time":610},""" +
            """"legs":[{"maneuvers":[""" +
            """{"instruction":"Continuez tout droit sur Rue de Rivoli.","length":0.1,"time":80},""" +
            """{"instruction":"Vous êtes arrivé à destination.","length":0.0,"time":0}""" +
            """]}]}}"""
    }
}
