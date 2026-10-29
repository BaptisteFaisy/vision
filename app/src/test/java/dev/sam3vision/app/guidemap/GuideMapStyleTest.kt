package dev.sam3vision.app.guidemap

import dev.sam3vision.core.model.RoutePoint
import dev.sam3vision.core.nav.ManeuverMarker
import dev.sam3vision.core.nav.MapOverlays
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Style local, gabarits et géométrie pure de la carte (JUnit seul, sans Android). */
class GuideMapStyleTest {

    private val paris = RoutePoint(48.8566, 2.3522)

    private fun overlays(
        route: List<RoutePoint> = emptyList(),
        destination: RoutePoint? = null,
        position: RoutePoint? = null,
        accuracyM: Float? = null,
        crossings: List<RoutePoint> = emptyList(),
        signals: List<RoutePoint> = emptyList(),
        maneuvers: List<ManeuverMarker> = emptyList(),
    ) = MapOverlays(route, destination, position, accuracyM, crossings, signals, maneuvers)

    @Test
    fun `styleJson est un style v8 raster https sans cle`() {
        val json = JSONObject(GuideMapStyle.styleJson())
        assertEquals(8, json.getInt("version"))
        val source = json.getJSONObject("sources").getJSONObject("osm")
        assertEquals("raster", source.getString("type"))
        assertEquals(OSM_MAX_ZOOM, source.getInt("maxzoom"))
        val tiles = source.getJSONArray("tiles")
        assertEquals(3, tiles.length())
        for (i in 0 until tiles.length()) {
            val url = tiles.getString(i)
            assertTrue(url.startsWith("https://"))
            assertTrue("{z}" in url && "{x}" in url && "{y}" in url)
            assertFalse("{r}" in url)
            assertFalse("key" in url.lowercase())
            assertTrue("tile.openstreetmap.fr/osmfr" in url)
        }
        val layers = json.getJSONArray("layers")
        assertEquals(1, layers.length())
        assertEquals("osm", layers.getJSONObject(0).getString("source"))
    }

    @Test
    fun `styleJson porte la mention legale OSM et OSM France`() {
        val json = JSONObject(GuideMapStyle.styleJson())
        val attribution = json.getJSONObject("sources").getJSONObject("osm").getString("attribution")
        assertTrue("OpenStreetMap" in attribution)
        assertTrue("OpenStreetMap France" in attribution)
        assertTrue("OpenStreetMap" in OSM_ATTRIBUTION_SHORT)
    }

    @Test
    fun `tileUrl formate z x y`() {
        assertEquals(
            "https://a.tile.openstreetmap.fr/osmfr/12/3/4.png",
            GuideMapStyle.tileUrl(12, 3, 4),
        )
    }

    @Test
    fun `isPlottable accepte Paris refuse degats`() {
        assertTrue(paris.isPlottable())
        assertFalse(RoutePoint(Double.NaN, 2.0).isPlottable())
        assertFalse(RoutePoint(48.0, Double.POSITIVE_INFINITY).isPlottable())
        assertFalse(RoutePoint(91.0, 2.0).isPlottable())
        assertFalse(RoutePoint(48.0, 181.0).isPlottable())
    }

    @Test
    fun `collectFramePoints rassemble tout sauf nuls et non tracables`() {
        val bad = RoutePoint(Double.NaN, 0.0)
        val dest = RoutePoint(48.86, 2.36)
        val points = collectFramePoints(
            overlays(
                route = listOf(paris, bad),
                destination = dest,
                crossings = listOf(bad),
                signals = listOf(paris),
                maneuvers = listOf(ManeuverMarker(dest, "Tournez", true)),
            ),
        )
        assertEquals(listOf(paris, dest, paris, dest), points)
    }

    @Test
    fun `collectFramePoints vide sans contenu`() {
        assertTrue(collectFramePoints(overlays()).isEmpty())
        assertTrue(collectFramePoints(overlays(route = listOf(RoutePoint(95.0, 0.0)))).isEmpty())
    }

    @Test
    fun `accuracyRing est ferme et au bon rayon`() {
        val ring = accuracyRing(paris, 100f)
        assertEquals(33, ring.size)
        assertEquals(ring.first(), ring.last())
        assertTrue(ring.all { it.isPlottable() })
        // Premier sommet plein est : écart en longitude reconverti en mètres.
        val east = ring.first()
        val meters = (east.lon - paris.lon) * 111_320.0 * kotlin.math.cos(Math.toRadians(paris.lat))
        assertEquals(100.0, meters, 1.0)
    }

    @Test
    fun `accuracyRing vide si centre ou rayon invalide`() {
        assertTrue(accuracyRing(RoutePoint(Double.NaN, 0.0), 10f).isEmpty())
        assertTrue(accuracyRing(paris, 0f).isEmpty())
        assertTrue(accuracyRing(paris, -5f).isEmpty())
        assertTrue(accuracyRing(paris, Float.NaN).isEmpty())
    }

    @Test
    fun `defauts Paris tracable zooms finis`() {
        assertTrue(GUIDE_DEFAULT_TARGET.isPlottable())
        assertTrue(GUIDE_DEFAULT_ZOOM.isFinite())
        assertTrue(GUIDE_SINGLE_POINT_ZOOM.isFinite())
    }
}
