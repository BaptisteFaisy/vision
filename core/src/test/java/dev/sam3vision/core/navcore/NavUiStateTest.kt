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
    fun `Navigating annonce Navigation vers X`() {
        val state = NavUiState.Navigating("la gare de Lyon")
        assertEquals("Navigation", state.overlayTitle)
        assertEquals("Navigation vers la gare de Lyon", state.overlayMessage)
        assertEquals("Navigation vers la gare de Lyon", state.announcement)
    }

    @Test
    fun `PlaceCard annonce Voici la fiche de X`() {
        val state = NavUiState.PlaceCard("la tour Eiffel")
        assertEquals("Navigation", state.overlayTitle)
        assertEquals("Voici la fiche de la tour Eiffel", state.overlayMessage)
        assertEquals("Voici la fiche de la tour Eiffel", state.announcement)
    }

    @Test
    fun `BrowserFallback affiche le repli mais annonce Navigation vers X`() {
        val state = NavUiState.BrowserFallback("Nice")
        assertEquals("Itinéraire ouvert dans le navigateur", state.overlayMessage)
        assertEquals("Navigation vers Nice", state.announcement)
    }

    @Test
    fun `Stopped annonce Guidage arrete`() {
        assertEquals("Navigation", NavUiState.Stopped.overlayTitle)
        assertEquals("Guidage arrêté", NavUiState.Stopped.overlayMessage)
        assertEquals("Guidage arrêté", NavUiState.Stopped.announcement)
    }

    @Test
    fun `Failed affiche Erreur rouge et parle par defaut`() {
        val state = NavUiState.Failed("Erreur. timeout Meta. 401 Scribe.")
        assertEquals("Erreur", state.overlayTitle)
        assertEquals("Erreur. timeout Meta. 401 Scribe.", state.overlayMessage)
        assertEquals("Erreur. timeout Meta. 401 Scribe.", state.announcement)
    }

    @Test
    fun `Failed muet sur panne TTS overlay seul`() {
        val state = NavUiState.Failed(NavMessages.GUIDANCE_UNAVAILABLE, speak = false)
        assertEquals("Je ne peux pas ouvrir le guidage", state.overlayMessage)
        assertNull(state.announcement)
    }
}
