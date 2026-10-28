package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Verrou JVM des bornes d'arrivée (chantier GPS-iSee, lot moteur) : rayon
 * par défaut 20 m ([NavTuning.arrivalRadiusM]) — à 21 m pas d'arrivée, à
 * 20 m arrivé — annonce [NavEvent.Arrived] unique, arrivée prioritaire sur
 * la progression. Pur JVM, déterministe.
 *
 * Les fixtures sont calculées (parallèle à la destination, segment
 * est-ouest) puis leur distance est verrouillée par haversine : si le
 * rayon par défaut change, ces tests cassent — c'est leur rôle.
 */
class VoiceNavArrivalBoundaryTest {

    private companion object {
        const val DEST_LAT = 48.85090
        const val DEST_LON = 2.35135
    }

    private val start = NavManeuver(ManeuverAction.Start, 48.85000, 2.35000)
    private val turn = NavManeuver(ManeuverAction.TurnRight, 48.85090, 2.35000, "rue de la Paix")
    private val arrive = NavManeuver(ManeuverAction.Arrive, DEST_LAT, DEST_LON)

    private val route = NavRoute(
        maneuvers = listOf(start, turn, arrive),
        destinationName = "la gare",
    )

    private fun fix(lat: Double, lon: Double, atMs: Long, accM: Float = 8f) =
        NavFix(lat = lat, lon = lon, accM = accM, atMs = atMs)

    private fun engine(): VoiceNavEngine {
        val engine = VoiceNavEngine()
        assertTrue(engine.start(route).isSuccess)
        return engine
    }

    /** Longitude à [meters] à l'ouest de la destination, sur le segment. */
    private fun lonWestOfDest(meters: Double): Double {
        val kx = NavGeo.EARTH_RADIUS_M * cos(Math.toRadians(DEST_LAT))
        return DEST_LON - Math.toDegrees(meters / kx)
    }

    private fun distToDest(lat: Double, lon: Double): Double =
        NavGeo.haversineMeters(lat, lon, DEST_LAT, DEST_LON)

    @Test
    fun `borne — a 21 m pas d'arrivee, guidage continue`() {
        val engine = engine()
        val lon21 = lonWestOfDest(21.0)
        assertEquals(21.0, distToDest(DEST_LAT, lon21), 0.05)

        val events = engine.onFix(fix(DEST_LAT, lon21, 1L))
        assertTrue(events.none { it is NavEvent.Arrived })
        assertEquals(NavState.Guiding, engine.state)
    }

    @Test
    fun `borne — a 20 m arrive`() {
        val engine = engine()
        // À mi-segment, encore loin : pas d'arrivée prématurée.
        val lon50 = lonWestOfDest(50.0)
        assertEquals(50.0, distToDest(DEST_LAT, lon50), 0.05)
        val mid = engine.onFix(fix(DEST_LAT, lon50, 1L))
        assertTrue(mid.none { it is NavEvent.Arrived })

        // Borne 20 m : 1 cm en dedans pour absorber l'arrondi flottant
        // (le GPS réel est au mètre, le verrou reste la borne 20 m).
        val lon20 = lonWestOfDest(20.0 - 0.01)
        val dist20 = distToDest(DEST_LAT, lon20)
        assertEquals(20.0, dist20, 0.05)
        assertTrue("fixture hors borne : $dist20", dist20 <= 20.0)
        val done = engine.onFix(fix(DEST_LAT, lon20, 2L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)
    }

    @Test
    fun `annonce unique — arrive une seule fois puis silence`() {
        val engine = engine()
        val done = engine.onFix(fix(DEST_LAT, DEST_LON, 1L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)

        // Fixes suivants (même sur place) : aucun événement.
        assertTrue(engine.onFix(fix(DEST_LAT, DEST_LON, 2L)).isEmpty())
        assertTrue(engine.onFix(fix(DEST_LAT, DEST_LON, 3L)).isEmpty())
        assertEquals(NavState.Finished, engine.state)
        assertNull(engine.nextManeuver())
    }

    @Test
    fun `priorite — arrivee des le premier fix, avant toute manoeuvre`() {
        val engine = engine()
        // Premier fix à 10 m de la destination, virage jamais validé :
        // l'arrivée gagne sur la progression.
        val lon10 = lonWestOfDest(10.0)
        assertEquals(10.0, distToDest(DEST_LAT, lon10), 0.05)

        val done = engine.onFix(fix(DEST_LAT, lon10, 1L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)
        assertNull(engine.nextManeuver())
    }
}
