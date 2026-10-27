package dev.sam3vision.app.ihear

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
        val state = IhearUiState(status = "Question failed: no network")
        assertEquals("Question failed: no network", statusLineOf(state))
    }

    @Test
    fun `le statut passe avant l'attente de la reflexion`() {
        // Un échec pendant la réflexion doit se lire, pas être masqué par « Thinking… ».
        val state = IhearUiState(status = "No LLM key configured", thinking = true)
        assertEquals("No LLM key configured", statusLineOf(state))
    }

    @Test
    fun `la reflexion sans statut affiche l'attente`() {
        assertEquals("Thinking…", statusLineOf(IhearUiState(thinking = true)))
    }

    @Test
    fun `un statut vide ou blanc ne compte pas`() {
        assertNull(statusLineOf(IhearUiState()))
        assertNull(statusLineOf(IhearUiState(status = "")))
        assertEquals("Thinking…", statusLineOf(IhearUiState(status = "   ", thinking = true)))
    }

    @Test
    fun `sans statut ni reflexion il n'y a pas de ligne`() {
        assertNull(statusLineOf(IhearUiState(holding = true)))
        assertNull(statusLineOf(IhearUiState(recordingVideo = true, photoPath = "/tmp/p.jpg")))
    }
}