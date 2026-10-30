package dev.sam3vision.app.isee

import dev.sam3vision.core.live.GuidanceDecision
import dev.sam3vision.core.live.GuidancePulse
import dev.sam3vision.core.live.GuidanceSide
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.live.PlacedLabel
import dev.sam3vision.core.live.SteeringCap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Battement : décision + plus proche droit devant, rien avant la première frame. */
class ProbePulseTest {

    private fun decision() = GuidanceDecision(
        blocked = true,
        obstacle = PlacedLabel("personne", 0.5f, 0.5f, 2f),
        side = GuidanceSide.Left,
        cap = SteeringCap.Straight,
        passage = SteeringCap.Left,
        shiftMeters = 1.5f,
    )

    private fun placed() = listOf(
        PlacedLabel("personne", 0.5f, 0.5f, 2.4f),
        PlacedLabel("chaise", 0.1f, 0.5f, 1.2f),
    )

    private fun pulseBox(): Pair<ProbePulse, MutableList<GuidancePulse?>> {
        val seen = mutableListOf<GuidancePulse?>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        return ProbePulse(scope, clockMs = { 1_500L }, publish = seen::add) to seen
    }

    @Test
    fun `rien avant la premiere decision`() {
        val (pump, _) = pulseBox()

        assertNull(pump.current(1_500L))
    }

    @Test
    fun `la frame nourrit mot et metres du couloir`() {
        val (pump, _) = pulseBox()
        pump.feed(1_000L, decision(), placed())

        val pulse = pump.current(1_500L)!!

        assertEquals(GuidanceWord.Left, pulse.word)
        assertEquals(2.4f, pulse.nearestMeters)
    }

    @Test
    fun `couloir vide repli sur le plus proche global`() {
        val (pump, _) = pulseBox()
        pump.feed(1_000L, decision(), listOf(PlacedLabel("chaise", 0.1f, 0.5f, 1.2f)))

        assertEquals(1.2f, pump.current(1_500L)!!.nearestMeters)
    }

    @Test
    fun `reset oublie la decision`() {
        val (pump, _) = pulseBox()
        pump.feed(1_000L, decision(), placed())
        pump.reset()

        assertNull(pump.current(1_500L))
    }
}
