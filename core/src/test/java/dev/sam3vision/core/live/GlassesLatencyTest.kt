package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Latence du lien lunettes : le décalage d'horloges se cale sur le minimum observé
 * (pas de NTP possible), et le perçu se lit en p50/p95/p99 sur une fenêtre de 100.
 */
class GlassesLatencyTest {

    @Test
    fun `la premiere observation cale le decalage, retard nul`() {
        val offset = GlassesClockOffset()
        assertEquals(0L, offset.observe(arrivalNs = 1_000_000_000L, presentationTimeUs = 900_000L))
        assertEquals(100_000_000L, offset.offsetNs)
        assertEquals(1L, offset.samples)
    }

    @Test
    fun `le retard variable est l excedent au-dessus du minimum`() {
        val offset = GlassesClockOffset()
        offset.observe(arrivalNs = 1_000_000_000L, presentationTimeUs = 900_000L)
        // Même pts, arrivée 25 ms plus tard : le lien a jitteré de 25 ms.
        assertEquals(25_000_000L, offset.observe(arrivalNs = 1_025_000_000L, presentationTimeUs = 900_000L))
        // Un transit plus court recalibre le minimum (retard nul).
        assertEquals(0L, offset.observe(arrivalNs = 990_000_000L, presentationTimeUs = 900_000L))
        assertEquals(90_000_000L, offset.offsetNs)
    }

    @Test
    fun `un pts absent ou debordant ne compte pas`() {
        val offset = GlassesClockOffset()
        assertNull(offset.observe(arrivalNs = 1_000L, presentationTimeUs = 0L))
        assertNull(offset.observe(arrivalNs = 1_000L, presentationTimeUs = -5L))
        assertNull(offset.observe(arrivalNs = 1_000L, presentationTimeUs = Long.MAX_VALUE))
        assertNull(offset.offsetNs)
        assertEquals(0L, offset.samples)
    }

    @Test
    fun `reset oublie le decalage entre deux sessions`() {
        val offset = GlassesClockOffset()
        offset.observe(arrivalNs = 1_000_000_000L, presentationTimeUs = 900_000L)
        offset.reset()
        assertNull(offset.offsetNs)
        assertEquals(0L, offset.samples)
    }

    @Test
    fun `le p99 vaut la 99e mesure sur une fenetre pleine de 100`() {
        val stats = GlassesLinkStats()
        for (i in 1..100) stats.record(linkUs = i.toLong(), decodeUs = 0L, displayUs = 0L)
        assertEquals(50L, stats.perceivedP50Us)
        assertEquals(95L, stats.perceivedP95Us)
        assertEquals(99L, stats.perceivedP99Us)
        assertEquals(95L, stats.linkP95Us)
        assertEquals(100L, stats.samples)
    }

    @Test
    fun `le percu additionne les trois etages, bornes a zero`() {
        val stats = GlassesLinkStats()
        stats.record(linkUs = -5L, decodeUs = 10L, displayUs = 7L)
        assertEquals(17L, stats.perceivedP50Us)
    }

    @Test
    fun `les abandons se comptent par cause, taux sur les frames vues`() {
        val stats = GlassesLinkStats()
        stats.record(linkUs = 1L, decodeUs = 1L, displayUs = 1L)
        stats.drop(LinkDrop.THROTTLE)
        stats.drop(LinkDrop.THROTTLE)
        stats.drop(LinkDrop.CONFLATED)
        val counts = stats.dropCounts()
        assertEquals(2L, counts[LinkDrop.THROTTLE.ordinal])
        assertEquals(1L, counts[LinkDrop.CONFLATED.ordinal])
        assertEquals(3L, stats.dropped)
        assertEquals(0.75, stats.dropRate, 1e-9)
    }

    @Test
    fun `la ligne de resume porte p50 p95 p99 et les abandons`() {
        val stats = GlassesLinkStats()
        stats.record(linkUs = 30_000L, decodeUs = 10_000L, displayUs = 8_000L)
        stats.drop(LinkDrop.DECODE_FAIL)
        val line = stats.line()
        assertTrue(line.startsWith("GlassesLink e2e p50/p95/p99 48000/48000/48000 us"))
        assertTrue(line.contains("drops 1+1"))
    }

    @Test
    fun `reset oublie mesures et abandons`() {
        val stats = GlassesLinkStats()
        stats.record(linkUs = 1L, decodeUs = 1L, displayUs = 1L)
        stats.drop(LinkDrop.ENCODE_FAIL)
        stats.reset()
        assertEquals(0L, stats.samples)
        assertEquals(0L, stats.dropped)
        assertEquals(0.0, stats.dropRate, 0.0)
        assertEquals(0L, stats.perceivedP99Us)
    }

    @Test
    fun `p99 vaut le max sur une fenetre de 20, d ou la fenetre 100 du lien`() {
        // Rang = ceil(0.99 × 20) = 20 : sur la fenêtre du direct, le p99 n'apprend rien.
        assertEquals(19, Percentiles.rank(Percentiles.P99, 20))
        assertEquals(98, Percentiles.rank(Percentiles.P99, 100))
    }
}
