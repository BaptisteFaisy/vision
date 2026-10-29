package dev.sam3vision.core.net

import dev.sam3vision.core.nav.MapBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSignalsTest {

    private val bounds = MapBounds(48.84, 48.86, 2.33, 2.35)

    @Test
    fun `chemin signaux avec cadre decimal`() {
        assertEquals(
            "/signals?s=48.84000&w=2.33000&n=48.86000&e=2.35000",
            signalsPath(bounds),
        )
    }

    @Test
    fun `feux parses entrees pourries ignorees`() {
        val raw = """{"signals":[
            {"lat":48.85,"lon":2.34},
            {"lat":48.86,"lon":2.35},
            {"lat":"x","lon":2.0},
            {"lon":2.0}
        ],"cached":false}"""

        val signals = parseSignalsJson(raw)

        assertEquals(2, signals.size)
        assertEquals(48.85, signals[0].lat, 1e-9)
        assertEquals(2.34, signals[0].lon, 1e-9)
    }

    @Test
    fun `sans signaux liste vide`() {
        assertTrue(parseSignalsJson("""{"signals":[],"cached":true}""").isEmpty())
    }

    @Test(expected = ApiError.BadResponse::class)
    fun `json illisible refuse`() {
        parseSignalsJson("pas du json")
    }

    @Test(expected = ApiError.BadResponse::class)
    fun `sans cle signals refuse`() {
        parseSignalsJson("""{"cached":false}""")
    }
}
