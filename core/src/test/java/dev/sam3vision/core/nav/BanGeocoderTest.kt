package dev.sam3vision.core.nav

import dev.sam3vision.core.net.ApiError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verrouille le repli BAN ([BanGeocoder]) : URL encodée, lecture GeoJSON
 * tolérante (`[lon, lat]`, `label`/`score`), coordonnées validées.
 */
class BanGeocoderTest {

    @Test
    fun `l'url encode la requete et porte la limite`() {
        val url = BanGeocoder.buildUrl("https://api-adresse.data.gouv.fr/", "12 rue de Rivoli, Paris", 3)
        assertEquals(
            "https://api-adresse.data.gouv.fr/search/?q=12+rue+de+Rivoli%2C+Paris&limit=3",
            url,
        )
    }

    @Test
    fun `la limite est bornee entre 1 et 10`() {
        assertTrue(BanGeocoder.buildUrl(BASE, "gare", 99).endsWith("limit=10"))
        assertTrue(BanGeocoder.buildUrl(BASE, "gare", 0).endsWith("limit=1"))
    }

    @Test
    fun `deux candidats lus avec label et score, ordre conserve`() {
        val places = BanGeocoder.parsePlaces(FIXTURE)
        assertEquals(2, places.size)
        assertEquals(48.844303, places[0].latitude, 1e-9)
        assertEquals(2.374377, places[0].longitude, 1e-9)
        assertEquals("Gare de Lyon 75012 Paris", places[0].label)
        assertEquals(0.94f, places[0].score)
        assertEquals("Gare de Lyon, Lyon", places[1].label)
        assertNull(places[1].score)
    }

    @Test
    fun `les candidats hors limites ou sans geometrie sont jetes`() {
        // Le 3e (lon 999) et le 4e (sans géométrie) du fixture sont absents.
        assertEquals(2, BanGeocoder.parsePlaces(FIXTURE).size)
    }

    @Test
    fun `une collection vide rend une liste vide`() {
        assertTrue(BanGeocoder.parsePlaces("""{"type":"FeatureCollection","features":[]}""").isEmpty())
        assertTrue(BanGeocoder.parsePlaces("""{}""").isEmpty())
    }

    @Test
    fun `un corps non JSON leve BadResponse`() {
        try {
            BanGeocoder.parsePlaces("pas du json {{{")
            fail("parsePlaces doit lever sur un corps illisible")
        } catch (e: ApiError.BadResponse) {
            assertTrue(e.message!!.contains("JSON illisible"))
        }
    }

    @Test
    fun `les bornes terrestres sont validees`() {
        assertTrue(BanGeocoder.isValidLatLon(48.85, 2.35))
        assertTrue(BanGeocoder.isValidLatLon(-90.0, -180.0))
        assertTrue(BanGeocoder.isValidLatLon(90.0, 180.0))
        assertTrue(!BanGeocoder.isValidLatLon(90.1, 0.0))
        assertTrue(!BanGeocoder.isValidLatLon(0.0, 180.1))
        assertTrue(!BanGeocoder.isValidLatLon(Double.NaN, 0.0))
    }

    companion object {
        private const val BASE = "https://api-adresse.data.gouv.fr"

        private const val FIXTURE = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [2.374377, 48.844303]},
                  "properties": {"label": "Gare de Lyon 75012 Paris", "score": 0.94,
                    "name": "Gare de Lyon", "city": "Paris"}
                },
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [4.874, 45.748]},
                  "properties": {"name": "Gare de Lyon", "city": "Lyon"}
                },
                {
                  "type": "Feature",
                  "geometry": {"type": "Point", "coordinates": [999.0, 48.0]},
                  "properties": {"label": "Hors limites"}
                },
                {"type": "Feature", "properties": {"label": "Sans géométrie"}}
              ]
            }
        """
    }
}
