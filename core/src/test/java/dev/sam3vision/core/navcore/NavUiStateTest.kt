package dev.sam3vision.core.navcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verrouille les états UI ([NavUiState]) contre la spéc. voix § b/c :
 * overlay + annonce de chaque état, silence des états dictée, `speak = false`
 * sur panne TTS.
 */
class NavUiStateTest {

    @Test
    fun `Idle est silencieux sans overlay`() {
        assertNull(NavUiState.Idle.overlayTitle)
        assertNull(NavUiState.Idle.overlayMessage)
        assertNull(NavUiState.Idle.announcement)
    }

    @Test
    fun `Listening et Transcribing ne portent aucun texte`() {
        assertNull(NavUiState.Listening.overlayMessage)
        assertNull(NavUiState.Listening.announcement)
        assertNull(NavUiState.Transcribing.overlayMessage)
        assertNull(NavUiState.Transcribing.announcement)
    }

    @Test
    fun `Navigating annonce Navigating to X`() {
        val state = NavUiState.Navigating("la gare de Lyon")
        assertEquals("Navigation", state.overlayTitle)
        assertEquals("Navigating to la gare de Lyon", state.overlayMessage)
        assertEquals("Navigating to la gare de Lyon", state.announcement)
    }

    @Test
    fun `PlaceCard annonce Here is the page for X`() {
        val state = NavUiState.PlaceCard("la tour Eiffel")
        assertEquals("Navigation", state.overlayTitle)
        assertEquals("Here is the page for la tour Eiffel", state.overlayMessage)
        assertEquals("Here is the page for la tour Eiffel", state.announcement)
    }

    @Test
    fun `BrowserFallback affiche le repli mais annonce Navigating to X`() {
        val state = NavUiState.BrowserFallback("Nice")
        assertEquals("Route opened in the browser", state.overlayMessage)
        assertEquals("Navigating to Nice", state.announcement)
    }

    @Test
    fun `Stopped annonce Guidance stopped`() {
        assertEquals("Navigation", NavUiState.Stopped.overlayTitle)
        assertEquals("Guidance stopped", NavUiState.Stopped.overlayMessage)
        assertEquals("Guidance stopped", NavUiState.Stopped.announcement)
    }

    @Test
    fun `Failed affiche Error rouge et parle par defaut`() {
        val state = NavUiState.Failed("Error. timeout Meta. 401 Scribe.")
        assertEquals("Error", state.overlayTitle)
        assertEquals("Error. timeout Meta. 401 Scribe.", state.overlayMessage)
        assertEquals("Error. timeout Meta. 401 Scribe.", state.announcement)
    }

    @Test
    fun `Failed muet sur panne TTS overlay seul`() {
        val state = NavUiState.Failed(NavMessages.GUIDANCE_UNAVAILABLE, speak = false)
        assertEquals("I can't open the guidance", state.overlayMessage)
        assertNull(state.announcement)
    }
}
