package dev.sam3vision.core.live

import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mise en forme du journal caméra : heures relatives et repères de position. */
class TimelineTextTest {

    private fun track(
        label: String = "personne",
        id: Int = 1,
        firstAtMs: Long = 0L,
        lastAtMs: Long = 0L,
        centerX: Float = 0.1f,
        centerY: Float = 0.5f,
        frames: Int = 3,
    ) = SeenTrack(id, label, firstAtMs, lastAtMs, 0.9f, centerX, centerY, frames)

    @Test
    fun `le temps relatif est lisible`() {
        assertEquals("just now", TimelineText.ago(1_000L, 1_000L))
        assertEquals("3 s ago", TimelineText.ago(4_000L, 1_000L))
        assertEquals("1 min 05 s ago", TimelineText.ago(66_000L, 1_000L))
    }

    @Test
    fun `la position suit le cadran horaire`() {
        assertEquals("at 9 o'clock", TimelineText.position(track(centerX = 0.1f)))
        assertEquals("at 12 o'clock", TimelineText.position(track(centerX = 0.5f)))
        assertEquals("at 3 o'clock", TimelineText.position(track(centerX = 0.9f)))
    }

    @Test
    fun `la position anglaise suit le cadran`() {
        assertEquals("at 9 o'clock", TimelineText.position(track(centerX = 0.1f), SpeechLanguage.En))
        assertEquals("at 12 o'clock", TimelineText.position(track(centerX = 0.5f), SpeechLanguage.En))
    }

    @Test
    fun `une ligne de journal cite label position et heure`() {
        val summary = TimelineSummary(0L, 10_000L, listOf(track(firstAtMs = 1_000L, lastAtMs = 7_000L)))
        val line = TimelineText.describe(summary)

        assertTrue(line.contains("personne"))
        assertTrue(line.contains("9 o'clock"))
        assertTrue(line.contains("3 s ago"))
    }

    @Test
    fun `une fenetre vide le dit`() {
        val summary = TimelineSummary(0L, 10_000L, emptyList())
        assertTrue(TimelineText.describe(summary).contains("nothing visible"))
    }

    @Test
    fun `le contexte reunit la fenetre globale et le point focal`() {
        val global = TimelineSummary(0L, 10_000L, listOf(track()))
        val focus = TimelineSummary(4_000L, 6_000L, listOf(track(lastAtMs = 5_000L)))
        val context = TimelineText.context(global, focus, "5 s ago")

        assertTrue(context.contains("What the camera saw recently"))
        assertTrue(context.contains("5 s ago"))
    }

    @Test
    fun `l etiquette des pastilles est compacte`() {
        val label = TimelineText.chipLabel(8_000L, track(label = "chien", lastAtMs = 5_000L))
        assertEquals("chien · 3 s ago", label)
    }
}
