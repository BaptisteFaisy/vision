package dev.sam3vision.app

import dev.sam3vision.core.model.SeenTrack
import dev.sam3vision.core.model.TimelineSummary
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contexte du journal envoyé au LLM en mode vision seule : quand rien n'a été suivi,
 * il doit être `null` — « rien de visible » à côté d'une image pleine de monde
 * désorientait le modèle.
 */
class LiveMemoryContextTest {

    private fun track(label: String, lastAtMs: Long = 7_000L) =
        SeenTrack(1, label, firstAtMs = 1_000L, lastAtMs = lastAtMs, score = 0.9f, centerX = 0.2f, centerY = 0.5f, frames = 4)

    private fun summary(vararg tracks: SeenTrack) = TimelineSummary(0L, 10_000L, tracks.toList())

    @Test
    fun `journal vide ne produit aucun contexte`() {
        assertNull(timelineContextOrNull(summary(), null, null))
        assertNull(timelineContextOrNull(summary(), summary(), "À l'instant"))
    }

    @Test
    fun `journal non vide cite l objet suivi`() {
        val context = timelineContextOrNull(summary(track("personne")), null, null)

        assertNotNull(context)
        assertTrue(context.orEmpty().contains("personne"))
    }

    @Test
    fun `focus vide est ignore`() {
        val context = timelineContextOrNull(summary(track("chien")), summary(), "Il y a 5 s")

        assertNotNull(context)
        assertTrue(context.orEmpty().contains("chien"))
        assertFalse(context.orEmpty().contains("Au moment demandé"))
    }
}