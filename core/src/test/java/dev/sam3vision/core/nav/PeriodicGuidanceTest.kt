package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rappel de guidage (JUnit seul, pur JVM, déterministe) : itinéraire
 * jouet nord-sud (~100 m), fixes horodatés à la main, cap suivi injecté —
 * barre en relatif, repli absolu sans cap, traversée, cadence 5 s.
 */
class PeriodicGuidanceTest {

    private val north100 = NavManeuver(ManeuverAction.Continue, 48.85090, 2.35000)
    private val cross10 = NavManeuver(ManeuverAction.Continue, 48.85009, 2.35000, crossing = true)
    private val cross100 = NavManeuver(ManeuverAction.Continue, 48.85090, 2.35000, crossing = true)

    private fun fix(atMs: Long, headingDeg: Float? = null) =
        NavFix(lat = 48.85000, lon = 2.35000, accM = 8f, atMs = atMs, headingDeg = headingDeg)

    private fun actionTarget(action: ManeuverAction) =
        NavManeuver(action, 48.85090, 2.35000)

    @Test
    fun `cap aligne dit tout droit`() {
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L, 0f), north100))
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L, 29f), north100))
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L, 331f), north100))
    }

    @Test
    fun `ecart modere dit le cote`() {
        assertEquals(WalkDirective.Left, PeriodicGuidance().onFix(fix(1L, 40f), north100))
        assertEquals(WalkDirective.Right, PeriodicGuidance().onFix(fix(1L, 320f), north100))
        assertEquals(WalkDirective.Right, PeriodicGuidance().onFix(fix(1L, 210f), north100))
    }

    @Test
    fun `dos a la cible dit demi-tour`() {
        assertEquals(WalkDirective.TurnAround, PeriodicGuidance().onFix(fix(1L, 180f), north100))
        assertEquals(WalkDirective.TurnAround, PeriodicGuidance().onFix(fix(1L, 209f), north100))
        assertEquals(WalkDirective.TurnAround, PeriodicGuidance().onFix(fix(1L, 151f), north100))
    }

    @Test
    fun `sans cap consigne absolue de la manoeuvre`() {
        val recall = PeriodicGuidance(PeriodicTuning(repeatMs = 0L))
        val fix = fix(1L)
        assertEquals(WalkDirective.Left, recall.onFix(fix, actionTarget(ManeuverAction.TurnLeft)))
        assertEquals(WalkDirective.Left, recall.onFix(fix, actionTarget(ManeuverAction.SlightLeft)))
        assertEquals(WalkDirective.Right, recall.onFix(fix, actionTarget(ManeuverAction.TurnRight)))
        assertEquals(WalkDirective.Right, recall.onFix(fix, actionTarget(ManeuverAction.SlightRight)))
        assertEquals(WalkDirective.TurnAround, recall.onFix(fix, actionTarget(ManeuverAction.UTurn)))
        assertEquals(WalkDirective.Roundabout, recall.onFix(fix, actionTarget(ManeuverAction.Roundabout)))
        assertEquals(WalkDirective.Straight, recall.onFix(fix, actionTarget(ManeuverAction.Continue)))
        assertEquals(WalkDirective.Straight, recall.onFix(fix, actionTarget(ManeuverAction.Start)))
        assertEquals(WalkDirective.Straight, recall.onFix(fix, actionTarget(ManeuverAction.Arrive)))
    }

    @Test
    fun `traversee proche sans cap dit de traverser`() {
        assertEquals(WalkDirective.Cross, PeriodicGuidance().onFix(fix(1L), cross10))
    }

    @Test
    fun `traversee loin sans cap dit tout droit`() {
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L), cross100))
    }

    @Test
    fun `traversee proche avec cap aligne barre tout droit`() {
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L, 0f), cross10))
    }

    @Test
    fun `sur la cible on traverse tout droit`() {
        val underFoot = NavManeuver(ManeuverAction.TurnLeft, 48.850005, 2.35000)
        assertEquals(WalkDirective.Straight, PeriodicGuidance().onFix(fix(1L, 180f), underFoot))
    }

    @Test
    fun `cap infini se replie sur la manoeuvre`() {
        val recall = PeriodicGuidance()
        assertEquals(WalkDirective.Left, recall.onFix(fix(1L, Float.NaN), actionTarget(ManeuverAction.TurnLeft)))
    }

    @Test
    fun `cadence cinq secondes au premier fix qui suit`() {
        val recall = PeriodicGuidance()
        assertEquals(WalkDirective.Straight, recall.onFix(fix(1_000L, 0f), north100))
        assertNull(recall.onFix(fix(3_000L, 0f), north100))
        assertNull(recall.onFix(fix(5_999L, 0f), north100))
        assertEquals(WalkDirective.Straight, recall.onFix(fix(6_000L, 0f), north100))
    }

    @Test
    fun `annonce moteur recale la cadence`() {
        val recall = PeriodicGuidance()
        assertEquals(WalkDirective.Straight, recall.onFix(fix(1_000L, 0f), north100))
        recall.noteSpoken(2_000L)
        assertNull(recall.onFix(fix(6_000L, 0f), north100))
        assertEquals(WalkDirective.Straight, recall.onFix(fix(7_000L, 0f), north100))
    }

    @Test
    fun `sans cible ni fix exploitable silence`() {
        val recall = PeriodicGuidance()
        assertNull(recall.onFix(fix(1L, 0f), null))
        val noCoord = fix(1L, 0f).copy(lat = Double.NaN)
        assertNull(recall.onFix(noCoord, north100))
        val noAcc = fix(1L, 0f).copy(accM = 0f)
        assertNull(recall.onFix(noAcc, north100))
    }

    @Test
    fun `reset rouvre la parole`() {
        val recall = PeriodicGuidance()
        assertEquals(WalkDirective.Straight, recall.onFix(fix(1_000L, 0f), north100))
        recall.reset()
        assertEquals(WalkDirective.Straight, recall.onFix(fix(1_001L, 0f), north100))
    }

    @Test
    fun `wrap180 replie les tours`() {
        assertEquals(-170.0, wrap180(190.0), 0.0)
        assertEquals(170.0, wrap180(-190.0), 0.0)
        assertEquals(0.0, wrap180(360.0), 0.0)
        assertEquals(0.0, wrap180(720.0), 0.0)
        assertEquals(-180.0, wrap180(-540.0), 0.0)
        assertTrue(wrap180(Double.NaN).isNaN())
    }
}
