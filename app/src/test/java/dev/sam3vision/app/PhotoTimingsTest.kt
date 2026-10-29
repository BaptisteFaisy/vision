package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** La chronologie photo cumule les deltas dans l'ordre, en logcat comme à l'écran. */
class PhotoTimingsTest {

    @Test
    fun `le resume cumule les deltas dans l'ordre`() {
        var now = 1_000L
        val lines = mutableListOf<String>()
        val timings = PhotoTimings(clock = { now }, log = lines::add)
        timings.begin()
        now = 1_040L
        timings.mark("charge")
        now = 1_100L
        timings.mark("sam")
        now = 1_900L
        timings.mark("analyse")
        now = 2_000L
        val line = timings.emit()

        assertEquals("photo charge=40 sam=60 analyse=800 total=1000", line)
        assertEquals(listOf(line), lines)
        assertEquals(100L, timings.sinceStart("sam"))
        assertEquals(900L, timings.sinceStart("analyse"))
    }

    @Test
    fun `sans begin tout est inerte`() {
        val lines = mutableListOf<String>()
        val timings = PhotoTimings(clock = { 0L }, log = lines::add)
        timings.mark("charge")

        assertEquals("", timings.emit())
        assertEquals(emptyList<String>(), lines)
        assertEquals(-1L, timings.sinceStart("charge"))
    }
}
