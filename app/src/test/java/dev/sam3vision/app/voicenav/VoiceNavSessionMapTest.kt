package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doublure artisanale du lanceur : enregistre ce qui part réellement. */
private class MapFakeLauncher : NavigationLauncher {
    val launched = mutableListOf<NavDestination>()

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        launched += destination
        return Result.success(NavRoute.MapsApp)
    }

    override fun openPlace(query: String): Result<NavRoute> = Result.success(NavRoute.Browser)
}

/**
 * Mode navigation de la session : ouverture sans destination, fermeture à la
 * voix, et surtout les deux gardes — le mode ne vole jamais un guidage en
 * cours, et une destination dictée depuis la carte reste une destination.
 */
class VoiceNavSessionMapTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)

    private fun session(launcher: MapFakeLauncher = MapFakeLauncher()) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { true },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = { Result.success(summary) },
        router = NavigationRouter(),
        engineStart = { _, _ -> true },
        autoStartOnVoice = false,
    )

    @Test
    fun `entrer en mode navigation ouvre la carte sans rien dire`() {
        val session = session()

        assertTrue(session.enterMap())
        assertEquals(VoiceNavUiState.Map, session.state.value)
        assertTrue("le mode ne parle pas de lui-même", spoken.isEmpty())
    }

    @Test
    fun `entrer en mode navigation ne vole pas un guidage`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("emmène-moi à la gare")
        session.confirmDestination()
        assertTrue(session.state.value is VoiceNavUiState.Guiding)

        assertFalse(session.enterMap())
        assertTrue(session.state.value is VoiceNavUiState.Guiding)
    }

    @Test
    fun `quitter la carte referme sans annoncer un guidage arrete`() {
        val session = session()
        session.enterMap()

        assertTrue(session.leaveMap())
        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
        assertFalse("hors carte, la fermeture ne consomme rien", session.leaveMap())
    }

    @Test
    fun `une destination dictee depuis la carte ouvre la confirmation`() = runBlocking {
        val session = session()
        session.enterMap()

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))
        assertTrue(session.state.value is VoiceNavUiState.Confirming)
    }

    @Test
    fun `quitte la carte et arrete la navigation referment sans parler`() = runBlocking {
        val session = session()

        session.enterMap()
        assertTrue(session.onVoiceTranscript("quitte la carte"))
        assertEquals(VoiceNavUiState.Idle, session.state.value)

        session.enterMap()
        assertTrue(session.onVoiceTranscript("arrête la navigation"))
        assertEquals(VoiceNavUiState.Idle, session.state.value)

        assertTrue("aucune annonce de guidage arrêté", spoken.isEmpty())
    }

    @Test
    fun `hors session un ordre de carte n'est pas consomme`() = runBlocking {
        val session = session()

        assertFalse(session.onVoiceTranscript("quitte la carte"))
        assertEquals(VoiceNavUiState.Idle, session.state.value)
    }
}
