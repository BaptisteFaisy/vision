package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveStatsTest {

    @Test
    fun `stats vides valent zero`() {
        val stats = LiveStats()
        assertEquals(0, stats.samples)
        assertEquals(0L, stats.averageMs)
        assertEquals(0L, stats.p95Ms)
    }

    @Test
    fun `moyenne et p95 sur un jeu connu de vingt mesures`() {
        val stats = LiveStats()
        (1..20).forEach { stats.record(it * 10L) } // 10, 20, ..., 200
        assertEquals(20, stats.samples)
        assertEquals(105L, stats.averageMs)
        assertEquals(190L, stats.p95Ms) // 19e valeur triee sur 20
    }

    @Test
    fun `la fenetre est bornee a maxSamples et oublie les plus anciens`() {
        val stats = LiveStats(maxSamples = 20)
        (1..25).forEach { stats.record(it * 100L) } // 100..2500
        assertEquals(20, stats.samples)
        assertEquals(1550L, stats.averageMs) // fenetre 600..2500
        assertEquals(2400L, stats.p95Ms)
    }

    @Test
    fun `une seule mesure donne moyenne egale a p95`() {
        val stats = LiveStats()
        stats.record(420L)
        assertEquals(1, stats.samples)
        assertEquals(420L, stats.averageMs)
        assertEquals(420L, stats.p95Ms)
    }

    @Test
    fun `les valeurs negatives sont bornees a zero`() {
        val stats = LiveStats()
        stats.record(-50L)
        stats.record(50L)
        assertEquals(25L, stats.averageMs)
        assertEquals(50L, stats.p95Ms)
    }
}
