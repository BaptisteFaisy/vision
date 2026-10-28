package dev.sam3vision.core.ground

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundSeedsTest {

    private val w = SyntheticScene.W
    private val h = SyntheticScene.H

    @Test
    fun `fromGradient ensemence la rampe lisse des deux tiers bas`() {
        val depth = SyntheticScene.rampWithStep(w, h, 0.01f, 2f, h, 0f)
        val valid = BooleanArray(w * h) { true }

        val seeds = GroundSeeds.fromGradient(depth, w, h, valid)

        assertEquals((h - h / 3) * w, countTrue(seeds))
        assertTrue(seeds[20 * w])
        assertFalse(seeds[0])
    }

    @Test
    fun `fromGradient exclut la marche franche`() {
        val depth = SyntheticScene.rampWithStep(w, h, 0.01f, 2f, 32, 2f)
        val valid = BooleanArray(w * h) { true }

        val seeds = GroundSeeds.fromGradient(depth, w, h, valid)

        assertTrue(seeds[20 * w])
        assertFalse(seeds[32 * w])
        assertFalse(seeds[31 * w])
    }

    @Test
    fun `lowerHalf vaut moitie basse et valide`() {
        val valid = BooleanArray(w * h) { i -> i % 2 == 0 }

        val seeds = GroundSeeds.lowerHalf(w, h, valid)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                assertEquals("pixel $x,$y", y >= h / 2 && valid[i], seeds[i])
            }
        }
    }

    @Test
    fun `withFallback garde la primaire suffisante rend la moitie sinon`() {
        val valid = BooleanArray(w * h) { true }
        val rich = BooleanArray(w * h) { i -> i < 600 }
        val poor = BooleanArray(w * h) { i -> i < 10 }

        assertSame(rich, GroundSeeds.withFallback(rich, w, h, valid))
        val fallback = GroundSeeds.withFallback(poor, w, h, valid)
        val expected = GroundSeeds.lowerHalf(w, h, valid)
        for (i in expected.indices) {
            assertEquals("pixel $i", expected[i], fallback[i])
        }
    }
}
