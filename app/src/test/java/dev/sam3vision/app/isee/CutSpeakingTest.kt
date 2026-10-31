package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Régression : une alerte de sécurité coupait la lecture du chat sans rendre
 * le drapeau parlant (`player.stop()` ne termine jamais) — lecteur cru occupé
 * pour toujours, toute la voix douce restait muette après l'urgence.
 */
class CutSpeakingTest {

    @Test
    fun `coupe rend le drapeau parlant et garde les sous-titres`() {
        val cut = IseeUiState(speakingEntryId = 12L, speakingText = "Bonjour").cutSpeaking()

        assertNull(cut.speakingEntryId)
        assertEquals("Bonjour", cut.speakingText)
    }

    @Test
    fun `coupe a l arret ne change rien`() {
        val idle = IseeUiState()

        assertEquals(idle, idle.cutSpeaking())
    }
}
