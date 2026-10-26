package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaîne complète de la mémoire du direct : ce que la caméra a vu → journal →
 * contexte transmis au LLM pour une question sur le passé.
 */
class LiveMemoryFlowTest {

    private fun detection(label: String, x: Float): Detection =
        Detection(label, 0.9f, listOf(x * 100f, 0f, x * 100f + 20f, 20f), 100, 100)

    @Test
    fun `la fenetre demandee ne melange pas le passe et le present`() {
        val timeline = LiveTimeline()
        timeline.record(2_000L, listOf(detection("personne", 0.05f)))
        timeline.record(7_000L, listOf(detection("chien", 0.45f)))
        val now = 10_000L

        val global = timeline.recent(now, 30_000L)
        val focus = timeline.summary(5_000L, 7_500L)

        assertTrue(TimelineText.describe(global).contains("personne"))
        assertTrue(TimelineText.describe(global).contains("chien"))
        assertTrue(TimelineText.describe(focus).contains("chien"))
        assertFalse(TimelineText.describe(focus).contains("personne"))
    }

    @Test
    fun `le contexte annonce ce que la camera a vu et quand`() {
        val timeline = LiveTimeline()
        timeline.record(2_000L, listOf(detection("personne", 0.05f)))
        timeline.record(7_000L, listOf(detection("chien", 0.45f)))
        val now = 10_000L

        val context = TimelineText.context(
            timeline.recent(now, 30_000L),
            timeline.summary(5_000L, 8_000L),
            "Il y a 5 s",
        )

        assertTrue(context.contains("Ce que la caméra a vu récemment"))
        assertTrue(context.contains("Il y a 5 s"))
        assertTrue(context.contains("personne"))
        assertTrue(context.contains("chien"))
        assertTrue(context.contains("il y a 8 s"))
    }
}