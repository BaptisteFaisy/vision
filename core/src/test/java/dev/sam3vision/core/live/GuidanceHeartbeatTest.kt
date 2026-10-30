package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Battement du guidage continu : un pulse à chaque tick, frais ou périmé. */
class GuidanceHeartbeatTest {

    private fun bouché(meters: Float = 2f) = GuidanceDecision(
        blocked = true,
        obstacle = PlacedLabel("personne", 0.5f, 0.5f, meters),
        side = GuidanceSide.Left,
        cap = SteeringCap.Straight,
        passage = SteeringCap.Left,
        shiftMeters = 1.5f,
    )

    @Test
    fun `avant la premiere frame le pulse est perime mais emis`() {
        val pulse = GuidanceHeartbeat().tick(300L)

        assertTrue(pulse.stale)
        assertEquals(GuidanceWord.Straight, pulse.word)
        assertNull(pulse.side)
    }

    @Test
    fun `une frame pose une decision fraiche`() {
        val beat = GuidanceHeartbeat()
        beat.onFrame(1_000L, bouché())

        val pulse = beat.tick(1_000L)

        assertFalse(pulse.stale)
        assertEquals(0L, pulse.ageMs)
        assertEquals(GuidanceWord.Left, pulse.word)
        assertEquals(GuidanceSide.Left, pulse.side)
        assertEquals(1.5f, pulse.shiftMeters)
    }

    @Test
    fun `le tick repete la consigne meme inchangee age grandissant`() {
        val beat = GuidanceHeartbeat()
        beat.onFrame(1_000L, bouché())

        val first = beat.tick(1_300L)
        val second = beat.tick(1_600L)

        assertEquals(first.word, second.word)
        assertEquals(first.side, second.side)
        assertEquals(300L, first.ageMs)
        assertEquals(600L, second.ageMs)
        assertFalse(second.stale)
    }

    @Test
    fun `les distances du telephone rafraichissent le pulse entre deux frames`() {
        val beat = GuidanceHeartbeat()
        beat.onFrame(1_000L, bouché())
        beat.onDistances(2.4f)

        assertEquals(2.4f, beat.tick(1_300L).nearestMeters)

        beat.onDistances(1.9f)

        assertEquals(1.9f, beat.tick(1_600L).nearestMeters)
    }

    @Test
    fun `apres deux secondes sans frame le pulse est perime mais emis`() {
        val beat = GuidanceHeartbeat()
        beat.onFrame(1_000L, bouché())

        val pulse = beat.tick(3_001L)

        assertTrue(pulse.stale)
        assertEquals(GuidanceWord.Left, pulse.word)
        assertEquals(2_001L, pulse.ageMs)
    }

    @Test
    fun `reset oublie la decision le pulse redevient perime`() {
        val beat = GuidanceHeartbeat()
        beat.onFrame(1_000L, bouché())
        beat.onDistances(2f)
        beat.reset()

        val pulse = beat.tick(1_300L)

        assertTrue(pulse.stale)
        assertNull(pulse.nearestMeters)
    }
}
