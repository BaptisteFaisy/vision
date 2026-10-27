package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * La ligne de statut du bas de l'écran : le seul endroit où un échec (question
 * non envoyée, clé LLM absente, micro indisponible, rien entendu) devient visible.
 * Pure, donc vérifiée sur JVM sans instrumentation.
 */
class IseeStatusLineTest {

    @Test
    fun `un statut ecrit est montre tel quel`() {
        val state = IseeUiState(status = "Question failed: no network")
        assertEquals("Question failed: no network", statusLineOf(state))
    }

    @Test
    fun `le statut passe avant l'attente de la reflexion`() {
        // Un échec pendant la réflexion doit se lire, pas être masqué par « Thinking… ».
        val state = IseeUiState(status = "No LLM key configured", thinking = true)
        assertEquals("No LLM key configured", statusLineOf(state))
    }

    @Test
    fun `la reflexion sans statut affiche l'attente`() {
        assertEquals("Thinking…", statusLineOf(IseeUiState(thinking = true)))
    }

    @Test
    fun `un statut vide ou blanc ne compte pas`() {
        assertNull(statusLineOf(IseeUiState()))
        assertNull(statusLineOf(IseeUiState(status = "")))
        assertEquals("Thinking…", statusLineOf(IseeUiState(status = "   ", thinking = true)))
    }

    @Test
    fun `sans statut ni reflexion il n'y a pas de ligne`() {
        assertNull(statusLineOf(IseeUiState(holding = true)))
        assertNull(statusLineOf(IseeUiState(recordingVideo = true, photoPath = "/tmp/p.jpg")))
    }
}