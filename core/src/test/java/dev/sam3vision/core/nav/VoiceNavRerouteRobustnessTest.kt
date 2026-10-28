package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * Verrou JVM du re-routage (chantier GPS-iSee, lot moteur) : 3 fixes
 * suspects consécutifs ⇒ [NavEvent.OffRoute], recalcul réussi ⇒ guidage
 * repris jusqu'à l'arrivée, recalcul impossible ⇒ ancien tracé gardé puis
 * [NavEvent.BackOnRoute] au retour spontané. Pur JVM, déterministe.
 *
 * Itinéraire jouet repris de [VoiceNavEngineTest] (départ → à droite →
 * arrivée, ~200 m) : mêmes coordonnées, mêmes seuils par défaut
 * ([NavTuning]) — aucun doublon de logique, seules les séquences de fixes
 * sont propres à ce verrou.
 */
class VoiceNavRerouteRobustnessTest {

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

    /** Fix suspect : ~51 m à l'est du segment départ → virage. */
    private fun suspect(engine: VoiceNavEngine, lat: Double, atMs: Long): List<NavEvent> =
        engine.onFix(fix(lat, 2.35070, atMs))

    /** Longitude à [meters] à l'est du segment, à la latitude [atLat]. */
    private fun lonEastOfSegment(meters: Double, atLat: Double): Double {
        val kx = NavGeo.EARTH_RADIUS_M * cos(Math.toRadians(atLat))
        return 2.35000 + Math.toDegrees(meters / kx)
    }

    @Test
    fun `cycle complet — ecart, recalcul, arrivee a destination`() {
        val engine = engine()
        suspect(engine, 48.85045, 1L)
        suspect(engine, 48.85046, 2L)
        val off = suspect(engine, 48.85047, 3L)
        assertTrue(off.single() is NavEvent.OffRoute)
        assertEquals(NavState.OffRoute, engine.state)

        // Recalcul réussi : le guidage reprend vers la même destination.
        assertTrue(engine.reroute(route).isSuccess)
        assertEquals(NavState.Guiding, engine.state)
        assertEquals(ManeuverAction.TurnRight, engine.nextManeuver()?.action)

        // Destination tenue jusqu'au bout : virage puis arrivée.
        engine.onFix(fix(48.85090, 2.35000, 4L))
        assertEquals(ManeuverAction.Arrive, engine.nextManeuver()?.action)
        val done = engine.onFix(fix(48.85090, 2.35125, 5L))
        assertEquals(listOf(NavEvent.Arrived), done)
        assertEquals(NavState.Finished, engine.state)
    }

    @Test
    fun `recalcul impossible — ancien trace garde, retour spontane`() {
        val engine = engine()
        suspect(engine, 48.85045, 1L)
        suspect(engine, 48.85046, 2L)
        suspect(engine, 48.85047, 3L)
        assertEquals(NavState.OffRoute, engine.state)

        // Échec réseau côté appelant = pas d'itinéraire de rechange : le
        // recalcul refusé (candidat invalide) ne touche à rien.
        val bad = route.copy(maneuvers = listOf(start))
        assertTrue(engine.reroute(bad).isFailure)
        assertEquals(NavState.OffRoute, engine.state)
        assertEquals(ManeuverAction.TurnRight, engine.nextManeuver()?.action)

        // Retour sur l'ancien tracé (~4 m d'écart) : repris, une seule fois.
        val back = engine.onFix(fix(48.85050, 2.35005, 4L))
        assertTrue(back.single() is NavEvent.BackOnRoute)
        assertEquals(NavState.Guiding, engine.state)
        val next = engine.onFix(fix(48.85055, 2.35002, 5L))
        assertTrue(next.none { it is NavEvent.BackOnRoute })
    }

    @Test
    fun `hysteresis — entre 15 et 30 m ecarte reste ecarte`() {
        val engine = engine()
        suspect(engine, 48.85045, 1L)
        suspect(engine, 48.85046, 2L)
        suspect(engine, 48.85047, 3L)
        assertEquals(NavState.OffRoute, engine.state)

        // À ~20 m du segment : ni retour (seuil 15 m) ni nouvel écart.
        val lon20 = lonEastOfSegment(20.0, 48.85045)
        val cross = NavGeo.segmentMeters(48.85045, lon20, 48.85000, 2.35000, 48.85090, 2.35000)
        assertTrue("fixture hors bande 15-30 m : $cross", cross in 15.0..30.0)
        val mid = engine.onFix(fix(48.85045, lon20, 4L))
        assertTrue(mid.none { it is NavEvent.BackOnRoute || it is NavEvent.OffRoute })
        assertEquals(NavState.OffRoute, engine.state)

        // Sous 15 m : retour.
        val back = engine.onFix(fix(48.85050, 2.35005, 5L))
        assertTrue(back.single() is NavEvent.BackOnRoute)
        assertEquals(NavState.Guiding, engine.state)
    }

    @Test
    fun `suspects consecutifs exiges — un fix sain remet a zero`() {
        val engine = engine()
        suspect(engine, 48.85045, 1L)
        val second = suspect(engine, 48.85046, 2L)
        assertTrue(second.none { it is NavEvent.OffRoute })

        // Fix sain sur le segment : le compteur repart de zéro.
        val healthy = engine.onFix(fix(48.85045, 2.35000, 3L))
        assertTrue(healthy.none { it is NavEvent.OffRoute })
        suspect(engine, 48.85048, 4L)
        val fifth = suspect(engine, 48.85049, 5L)
        assertTrue(fifth.none { it is NavEvent.OffRoute })
        assertEquals(NavState.Guiding, engine.state)

        // Troisième suspect consécutif : écart déclaré.
        val off = suspect(engine, 48.85050, 6L).single() as NavEvent.OffRoute
        assertTrue("écart annoncé sous le seuil : ${off.crossTrackM}", off.crossTrackM > 30.0)
        assertEquals(NavState.OffRoute, engine.state)
    }
}
