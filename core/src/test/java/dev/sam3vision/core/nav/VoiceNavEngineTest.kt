package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moteur de guidage (JUnit seul, pur JVM, déterministe) : itinéraire
 * jouet de ~200 m (départ → à droite → arrivée), fixes horodatés à la
 * main — annonces loin/près, avance, écart + recalcul, arrivée.
 */
class VoiceNavEngineTest {

    private val start = NavManeuver(ManeuverAction.Start, 48.85000, 2.35000)
    private val turn = NavManeuver(ManeuverAction.TurnRight, 48.85090, 2.35000, "rue de la Paix")
    private val arrive = NavManeuver(ManeuverAction.Arrive, 48.85090, 2.35135)

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

    @Test
    fun `itineraire invalide refuse etat idle`() {
        val engine = VoiceNavEngine()
        val bad = route.copy(maneuvers = listOf(start))
        assertTrue(engine.start(bad).isFailure)
        assertEquals(NavState.Idle, engine.state)
        assertTrue(engine.onFix(fix(48.85, 2.35, 1L)).isEmpty())
    }

    @Test
    fun `annonce loin puis pres sans repetition`() {
        val engine = engine()
        // À ~100 m du virage : encore trop loin (seuil 100 m, marge).
        assertTrue(engine.onFix(fix(48.84995, 2.35, 1L)).isEmpty())
        // À ~50 m : annonce « de loin ».
        val far = engine.onFix(fix(48.85045, 2.35, 2L))
        assertEquals(1, far.size)
        val ahead = far.single() as NavEvent.ManeuverAhead
        assertEquals(ManeuverAction.TurnRight, ahead.action)
        assertEquals("rue de la Paix", ahead.street)
        assertEquals(false, ahead.near)
        // Même zone : pas de répétition.
        assertTrue(engine.onFix(fix(48.85050, 2.35, 3L)).isEmpty())
        // À ~17 m : rappel « de près ».
        val near = engine.onFix(fix(48.85075, 2.35, 4L))
        assertEquals(1, near.size)
        assertEquals(true, (near.single() as NavEvent.ManeuverAhead).near)
        assertTrue(engine.onFix(fix(48.85076, 2.35, 5L)).isEmpty())
    }

    @Test
    fun `virage valide puis arrivee`() {
        val engine = engine()
        // Sur le virage : avance, annonce l'arrivée « de loin » (~99 m).
        val passed = engine.onFix(fix(48.85090, 2.35000, 1L))
        assertEquals(ManeuverAction.Arrive, engine.nextManeuver()?.action)
        assertEquals(1, passed.size)
        assertEquals(ManeuverAction.Arrive, (passed.single() as NavEvent.ManeuverAhead).action)
        // À ~7 m de la destination : arrivé, une seule fois.
        val done = engine.onFix(fix(48.85090, 2.35125, 2L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)
        assertTrue(engine.onFix(fix(48.85090, 2.35125, 3L)).isEmpty())
    }

    @Test
    fun `ecart declare au troisieme suspect`() {
        val engine = engine()
        // ~51 m à l'est du segment : suspect, mais annonce « de loin » d'abord.
        val first = engine.onFix(fix(48.85045, 2.35070, 1L))
        assertTrue(first.single() is NavEvent.ManeuverAhead)
        assertTrue(engine.onFix(fix(48.85046, 2.35070, 2L)).isEmpty())
        val third = engine.onFix(fix(48.85047, 2.35070, 3L))
        assertEquals(1, third.size)
        assertTrue(third.single() is NavEvent.OffRoute)
        assertEquals(NavState.OffRoute, engine.state)
        // Retour sur l'itinéraire (~4 m d'écart) : de nouveau guidé.
        val back = engine.onFix(fix(48.85050, 2.35005, 4L))
        assertTrue(back.single() is NavEvent.BackOnRoute)
        assertEquals(NavState.Guiding, engine.state)
    }

    @Test
    fun `recalcul recharge le guidage`() {
        val engine = engine()
        repeat(3) { engine.onFix(fix(48.85045, 2.35070, it + 1L)) }
        assertEquals(NavState.OffRoute, engine.state)
        assertTrue(engine.reroute(route).isSuccess)
        assertEquals(NavState.Guiding, engine.state)
        assertEquals(ManeuverAction.TurnRight, engine.nextManeuver()?.action)
    }

    @Test
    fun `fixes grossiers perimes ou vides ignores`() {
        val engine = engine()
        assertTrue(engine.onFix(fix(48.85045, 2.35, 1L, accM = 50f)).isEmpty())
        // Le fix grossier n'a rien consommé : même horodatage accepté fin.
        assertEquals(1, engine.onFix(fix(48.85045, 2.35, 1L)).size)
        assertTrue(engine.onFix(fix(48.85045, 2.35, 1L)).isEmpty())
        assertTrue(engine.onFix(NavFix(Double.NaN, 2.35, 8f, 2L)).isEmpty())
    }
}
