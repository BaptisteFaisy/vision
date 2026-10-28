package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSummaryTest {

    @Test
    fun `metres ronds sous un kilometre`() {
        assertEquals("0 m", formatDistanceFr(0))
        assertEquals("350 m", formatDistanceFr(350))
        assertEquals("999 m", formatDistanceFr(999))
    }

    @Test
    fun `kilometres a une decimale avec virgule francaise`() {
        assertEquals("1,0 km", formatDistanceFr(1000))
        assertEquals("2,4 km", formatDistanceFr(2450))
        assertEquals("12,0 km", formatDistanceFr(12040))
    }

    @Test
    fun `distance negative ramenee a zero`() {
        assertEquals("0 m", formatDistanceFr(-5))
    }

    @Test
    fun `duree courte en minutes francaises`() {
        assertEquals("moins d'une minute", formatDurationFr(0))
        assertEquals("moins d'une minute", formatDurationFr(59))
        assertEquals("1 min", formatDurationFr(60))
        assertEquals("12 min", formatDurationFr(720))
    }

    @Test
    fun `duree longue en heures et minutes`() {
        assertEquals("1 h 00", formatDurationFr(3600))
        assertEquals("1 h 05", formatDurationFr(3900))
        assertEquals("2 h 30", formatDurationFr(9000))
    }

    @Test
    fun `duree negative ramenee a moins d'une minute`() {
        assertEquals("moins d'une minute", formatDurationFr(-30))
    }

    @Test
    fun `resume expose ses formats`() {
        val summary = RouteSummary("Gare de Lyon", "Place Louis-Armand, Paris", 2450, 900)

        assertEquals("2,4 km", summary.distanceFr())
        assertEquals("15 min", summary.durationFr())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resume refuse une destination blanche`() {
        RouteSummary("  ", null, 100, 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resume refuse une distance negative`() {
        RouteSummary("Gare", null, -1, 60)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resume refuse une duree negative`() {
        RouteSummary("Gare", null, 100, -1)
    }
}
