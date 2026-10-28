package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Géodésie du guidage (JUnit seul, pur JVM) : haversine, cap et écart au
 * segment sur des points connus, plus le contrat NaN (entrée non finie ⇒
 * NaN, jamais d'exception).
 */
class NavGeoTest {

    @Test
    fun `haversine paris tour eiffel vaut 4 km`() {
        // Notre-Dame (48.8530, 2.3499) → tour Eiffel (48.8584, 2.2945) ≈ 4,1 km.
        val meters = NavGeo.haversineMeters(48.8530, 2.3499, 48.8584, 2.2945)
        assertEquals(4100.0, meters, 150.0)
    }

    @Test
    fun `haversine meme point vaut zero`() {
        assertEquals(0.0, NavGeo.haversineMeters(48.85, 2.35, 48.85, 2.35), 0.001)
    }

    @Test
    fun `haversine un degre de latitude vaut 111 km`() {
        val meters = NavGeo.haversineMeters(48.0, 2.0, 49.0, 2.0)
        assertEquals(111_195.0, meters, 500.0)
    }

    @Test
    fun `cap nord est sud ouest en croix`() {
        assertEquals(0.0, NavGeo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.001)
        assertEquals(90.0, NavGeo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.001)
        assertEquals(180.0, NavGeo.bearingDeg(0.0, 0.0, -1.0, 0.0), 0.001)
        assertEquals(270.0, NavGeo.bearingDeg(0.0, 0.0, 0.0, -1.0), 0.001)
    }

    @Test
    fun `segment point sur la ligne vaut zero`() {
        val meters = NavGeo.segmentMeters(
            48.0, 2.005, 48.0, 2.0, 48.0, 2.01,
        )
        assertEquals(0.0, meters, 0.5)
    }

    @Test
    fun `segment ecart lateral mesure`() {
        // 0,001° de latitude d'écart ≈ 111 m.
        val meters = NavGeo.segmentMeters(
            48.001, 2.005, 48.0, 2.0, 48.0, 2.01,
        )
        assertEquals(111.2, meters, 1.0)
    }

    @Test
    fun `segment au-dela de l'extremite mesure jusqu'a elle`() {
        // P prolonge le segment vers l'est : ~111 m au-delà de B.
        val meters = NavGeo.segmentMeters(
            48.0, 2.011, 48.0, 2.0, 48.0, 2.01,
        )
        assertEquals(74.0, meters, 2.0)
    }

    @Test
    fun `segment degenere mesure jusqu'au point`() {
        val meters = NavGeo.segmentMeters(
            48.001, 2.0, 48.0, 2.0, 48.0, 2.0,
        )
        assertEquals(111.2, meters, 1.0)
    }

    @Test
    fun `entree non finie rend NaN partout`() {
        assertTrue(NavGeo.haversineMeters(Double.NaN, 2.0, 48.0, 2.0).isNaN())
        assertTrue(NavGeo.bearingDeg(48.0, 2.0, 48.0, Double.NaN).isNaN())
        assertTrue(
            NavGeo.segmentMeters(48.0, 2.0, 48.0, Double.NaN, 48.0, 2.0).isNaN(),
        )
    }
}
