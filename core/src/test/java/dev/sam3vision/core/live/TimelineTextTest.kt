package dev.sam3vision.core.live

import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
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
        assertEquals("à l'instant", TimelineText.ago(1_000L, 1_000L))
        assertEquals("il y a 3 s", TimelineText.ago(4_000L, 1_000L))
        assertEquals("il y a 1 min 05 s", TimelineText.ago(66_000L, 1_000L))
    }

    @Test
    fun `la position se lit de gauche a droite`() {
        assertTrue(TimelineText.position(track(centerX = 0.1f)).contains("gauche"))
        assertTrue(TimelineText.position(track(centerX = 0.5f)).contains("centre"))
        assertTrue(TimelineText.position(track(centerX = 0.9f)).contains("droite"))
    }

    @Test
    fun `la position mentionne la hauteur quand elle est nette`() {
        assertTrue(TimelineText.position(track(centerY = 0.1f)).contains("en haut"))
        assertTrue(TimelineText.position(track(centerY = 0.9f)).contains("en bas"))
    }

    @Test
    fun `une ligne de journal cite label position et heure`() {
        val summary = TimelineSummary(0L, 10_000L, listOf(track(firstAtMs = 1_000L, lastAtMs = 7_000L)))
        val line = TimelineText.describe(summary)

        assertTrue(line.contains("personne"))
        assertTrue(line.contains("gauche"))
        assertTrue(line.contains("il y a 3 s"))
    }

    @Test
    fun `une fenetre vide le dit`() {
        val summary = TimelineSummary(0L, 10_000L, emptyList())
        assertTrue(TimelineText.describe(summary).contains("rien de visible"))
    }

    @Test
    fun `le contexte reunit la fenetre globale et le point focal`() {
        val global = TimelineSummary(0L, 10_000L, listOf(track()))
        val focus = TimelineSummary(4_000L, 6_000L, listOf(track(lastAtMs = 5_000L)))
        val context = TimelineText.context(global, focus, "Il y a 5 s")

        assertTrue(context.contains("Ce que la caméra a vu récemment"))
        assertTrue(context.contains("Il y a 5 s"))
    }

    @Test
    fun `l etiquette des pastilles est compacte`() {
        val label = TimelineText.chipLabel(8_000L, track(label = "chien", lastAtMs = 5_000L))
        assertEquals("chien · il y a 3 s", label)
    }
}
