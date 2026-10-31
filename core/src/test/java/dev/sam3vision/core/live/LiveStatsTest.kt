package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStatsTest {

    @Test
    fun `stats vides valent zero`() {
        val stats = LiveStats()
        assertEquals(0, stats.samples)
        assertEquals(0L, stats.averageMs)
        assertEquals(0L, stats.p50Ms)
        assertEquals(0L, stats.p95Ms)
    }

    @Test
    fun `moyenne, p50 et p95 sur un jeu connu de vingt mesures`() {
        val stats = LiveStats()
        (1..20).forEach { stats.record(it * 10L) } // 10, 20, ..., 200
        assertEquals(20, stats.samples)
        assertEquals(105L, stats.averageMs)
        assertEquals(100L, stats.p50Ms) // 10e valeur triee sur 20
        assertEquals(190L, stats.p95Ms) // 19e valeur triee sur 20
    }

    @Test
    fun `le p50 suit la fenetre et le p95 sa queue haute`() {
        val stats = LiveStats()
        (1..20).forEach { stats.record(it * 100L) } // 100, 200, ..., 2000
        assertEquals(1000L, stats.p50Ms) // 10e sur 20
        assertEquals(1900L, stats.p95Ms) // 19e sur 20

        // Une seule grande mesure dans la fenetre : p95 la voit, p50 presque pas.
        stats.record(10_000L)
        assertEquals(1100L, stats.p50Ms) // 100 est sorti, 10 000 est au sommet
        assertEquals(2000L, stats.p95Ms)
    }

    @Test
    fun `la fenetre est bornee a maxSamples et oublie les plus anciens`() {
        val stats = LiveStats(maxSamples = 20)
        (1..25).forEach { stats.record(it * 100L) } // 100..2500
        assertEquals(20, stats.samples)
        assertEquals(1550L, stats.averageMs) // fenetre 600..2500
        assertEquals(1500L, stats.p50Ms) // 10e sur 20
        assertEquals(2400L, stats.p95Ms)
    }

    @Test
    fun `une seule mesure donne moyenne, p50 et p95 egaux`() {
        val stats = LiveStats()
        stats.record(420L)
        assertEquals(1, stats.samples)
        assertEquals(420L, stats.averageMs)
        assertEquals(420L, stats.p50Ms)
        assertEquals(420L, stats.p95Ms)
    }

    @Test
    fun `les valeurs negatives sont bornees a zero`() {
        val stats = LiveStats()
        stats.record(-50L)
        stats.record(50L)
        assertEquals(25L, stats.averageMs)
        assertEquals(0L, stats.p50Ms) // {-50 → 0, 50} : le 1er sur 2
        assertEquals(50L, stats.p95Ms)
    }

    @Test
    fun `ecarts vides valent zero`() {
        val stats = MotionGapStats()
        assertEquals(0, stats.samples)
        assertEquals(0f, stats.averagePx)
        assertEquals(0f, stats.p95Px)
    }

    @Test
    fun `ecarts moyenne et p95 en px sur vingt mesures`() {
        val stats = MotionGapStats()
        (1..20).forEach { stats.record(it.toFloat()) } // 1, 2, ..., 20 px
        assertEquals(20, stats.samples)
        assertEquals(10.5f, stats.averagePx)
        assertEquals(19f, stats.p95Px) // 19e sur 20
    }

    @Test
    fun `ecarts negatifs bornes et non finis jetes`() {
        val stats = MotionGapStats()
        stats.record(-4f)
        stats.record(Float.NaN)
        stats.record(6f)
        assertEquals(2, stats.samples) // NaN jeté, -4 → 0
        assertEquals(3f, stats.averagePx)
        stats.reset()
        assertEquals(0, stats.samples)
    }
}
