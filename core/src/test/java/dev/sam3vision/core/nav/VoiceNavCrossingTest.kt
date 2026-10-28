package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Traversées du moteur (JUnit seul, pur JVM, déterministe) : même jouet
 * que [VoiceNavEngineTest] (~200 m), la manœuvre intermédiaire portant
 * [NavManeuver.crossing] — préavis loin/près dédiés, passage signalé,
 * reprise du guidage ensuite.
 */
class VoiceNavCrossingTest {

    private val start = NavManeuver(ManeuverAction.Start, 48.85000, 2.35000)
    private val crossing = NavManeuver(ManeuverAction.Continue, 48.85090, 2.35000, crossing = true)
    private val arrive = NavManeuver(ManeuverAction.Arrive, 48.85090, 2.35135)

    private val route = NavRoute(
        maneuvers = listOf(start, crossing, arrive),
        destinationName = "la gare",
    )

    private fun fix(lat: Double, lon: Double, atMs: Long, accM: Float = 8f) =
        NavFix(lat = lat, lon = lon, accM = accM, atMs = atMs)

    private fun engine(): VoiceNavEngine {
        val engine = VoiceNavEngine()
        assertTrue(engine.start(route).isSuccess)
        return engine
    }

    @Test
    fun `traversee annoncee loin puis pres jamais en manoeuvre`() {
        val engine = engine()
        // À ~106 m : encore trop loin (seuil 100 m, marge).
        assertTrue(engine.onFix(fix(48.84995, 2.35, 1L)).isEmpty())
        // À ~50 m : préavis « de loin ».
        val far = engine.onFix(fix(48.85045, 2.35, 2L))
        assertEquals(1, far.size)
        val ahead = far.single() as NavEvent.CrossingAhead
        assertEquals(false, ahead.near)
        // Même zone : pas de répétition.
        assertTrue(engine.onFix(fix(48.85050, 2.35, 3L)).isEmpty())
        // À ~17 m : rappel « de près ».
        val near = engine.onFix(fix(48.85075, 2.35, 4L))
        assertEquals(1, near.size)
        assertEquals(true, (near.single() as NavEvent.CrossingAhead).near)
        assertTrue(engine.onFix(fix(48.85076, 2.35, 5L)).isEmpty())
    }

    @Test
    fun `traversee passee signalee puis guidage repris`() {
        val engine = engine()
        // Sur le passage : traversée passée + annonce de l'arrivée (~99 m).
        val passed = engine.onFix(fix(48.85090, 2.35000, 1L))
        assertEquals(2, passed.size)
        assertEquals(NavEvent.CrossingPassed, passed[0])
        assertEquals(ManeuverAction.Arrive, (passed[1] as NavEvent.ManeuverAhead).action)
        assertEquals(ManeuverAction.Arrive, engine.nextManeuver()?.action)
        // À ~7 m de la destination : arrivé, une seule fois.
        val done = engine.onFix(fix(48.85090, 2.35125, 2L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)
    }

    @Test
    fun `manoeuvre ordinaire sans traversee annoncee comme avant`() {
        val plain = route.copy(maneuvers = listOf(start, crossing.copy(crossing = false), arrive))
        val engine = VoiceNavEngine()
        assertTrue(engine.start(plain).isSuccess)

        val far = engine.onFix(fix(48.85045, 2.35, 1L))

        assertEquals(1, far.size)
        val ahead = far.single() as NavEvent.ManeuverAhead
        assertEquals(ManeuverAction.Continue, ahead.action)
        assertEquals(false, ahead.near)
    }
}
