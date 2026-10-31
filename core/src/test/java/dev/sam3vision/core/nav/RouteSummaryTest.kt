package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteSummaryTest {

    @Test
    fun `metres ronds sous un kilometre`() {
        assertEquals("0 m", formatDistance(0))
        assertEquals("350 m", formatDistance(350))
        assertEquals("999 m", formatDistance(999))
    }

    @Test
    fun `kilometres a une decimale avec point anglais`() {
        assertEquals("1.0 km", formatDistance(1000))
        assertEquals("2.4 km", formatDistance(2450))
        assertEquals("12.0 km", formatDistance(12040))
    }

    @Test
    fun `distance negative ramenee a zero`() {
        assertEquals("0 m", formatDistance(-5))
    }

    @Test
    fun `duree courte en minutes anglaises`() {
        assertEquals("less than a minute", formatDuration(0))
        assertEquals("less than a minute", formatDuration(59))
        assertEquals("1 min", formatDuration(60))
        assertEquals("12 min", formatDuration(720))
    }

    @Test
    fun `duree longue en heures et minutes`() {
        assertEquals("1 h 00", formatDuration(3600))
        assertEquals("1 h 05", formatDuration(3900))
        assertEquals("2 h 30", formatDuration(9000))
    }

    @Test
    fun `duree parlee en anglais`() {
        assertEquals("less than a minute", formatDurationEn(0))
        assertEquals("less than a minute", formatDurationEn(59))
        assertEquals("1 minute", formatDurationEn(60))
        assertEquals("12 minutes", formatDurationEn(720))
        assertEquals("1 hour", formatDurationEn(3600))
        assertEquals("1 hour, 5 minutes", formatDurationEn(3900))
        assertEquals("2 hours, 30 minutes", formatDurationEn(9000))
    }

    @Test
    fun `duree negative ramenee a less than a minute`() {
        assertEquals("less than a minute", formatDuration(-30))
    }

    @Test
    fun `resume expose ses formats`() {
        val summary = RouteSummary("Gare de Lyon", "Place Louis-Armand, Paris", 2450, 900)

        assertEquals("2.4 km", summary.distance())
        assertEquals("15 min", summary.duration())
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
