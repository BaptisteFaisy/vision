package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doublure artisanale du lanceur : enregistre les appels, échec programmable. */
private class FakeLauncher(var fail: Boolean = false) : NavigationLauncher {
    val launched = mutableListOf<NavDestination>()
    val opened = mutableListOf<String>()

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        launched += destination
        return if (fail) Result.failure(IllegalStateException("refusé")) else Result.success(NavRoute.MapsApp)
    }

    override fun openPlace(query: String): Result<NavRoute> {
        opened += query
        return if (fail) Result.failure(IllegalStateException("refusé")) else Result.success(NavRoute.Browser)
    }
}

class VoiceNavSessionTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)

    private fun session(
        launcher: FakeLauncher = FakeLauncher(),
        guidance: Boolean = true,
        loader: RouteLoader = { Result.success(summary) },
    ) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { guidance },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = loader,
        router = NavigationRouter(),
    )

    @Test
    fun `sans intention rien n'est consomme ni dit`() = runBlocking {
        val session = session()

        assertFalse(session.onVoiceTranscript("où est la pharmacie la plus proche ?"))
        assertFalse(session.onVoiceTranscript("   "))
        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `destination ouvre la confirmation avec resume et annonce`() = runBlocking {
        val session = session()

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals("la gare", state.destination)
        assertEquals(summary, state.summary)
        assertFalse(state.loading)
        assertEquals(listOf("Destination : la gare"), spoken)
    }

    @Test
    fun `chargeur en echec montre l'adresse seule`() = runBlocking {
        val session = session(loader = { Result.failure(IllegalStateException("moteur coupé")) })

        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals("la tour Eiffel", state.destination)
        assertEquals(null, state.summary)
        assertEquals("moteur coupé", state.error)
        assertEquals(listOf("Destination : la tour Eiffel"), spoken)
    }

    @Test
    fun `confirmer lance le guidage et annonce le depart`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher)
        session.onVoiceTranscript("naviguer vers la gare")

        session.confirmDestination()

        assertEquals(listOf(NavDestination.Query("la gare")), launcher.launched)
        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals("la gare", state.destination)
        assertEquals("Direction la gare", state.update.instruction)
        assertEquals(listOf("Destination : la gare", "Navigation vers la gare"), spoken)
    }

    @Test
    fun `confirmer sans resume part quand meme`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher, loader = UnavailableRouteLoader)
        session.onVoiceTranscript("va à la gare")

        session.confirmDestination()

        assertEquals(1, launcher.launched.size)
        assertTrue(session.state.value is VoiceNavUiState.Guiding)
    }

    @Test
    fun `guidage eteint ouvre la fiche lieu et revient au repos`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher, guidance = false)
        session.onVoiceTranscript("va à la tour Eiffel")

        session.confirmDestination()

        assertEquals(listOf("la tour Eiffel"), launcher.opened)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(
            listOf("Destination : la tour Eiffel", "Voici la fiche de la tour Eiffel"),
            spoken,
        )
    }

    @Test
    fun `lancement refuse reste en confirmation avec motif`() = runBlocking {
        val session = session(FakeLauncher(fail = true))
        session.onVoiceTranscript("va à la gare")

        session.confirmDestination()

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, state.error)
        assertEquals(
            listOf("Destination : la gare", NavigationPhrases.GUIDANCE_UNAVAILABLE),
            spoken,
        )
    }

    @Test
    fun `annuler ferme sans rien dire`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("va à la gare")
        spoken.clear()

        session.cancelConfirmation()

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `mises a jour du moteur affichees jusqu'a l'arrivee`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        spoken.clear()

        session.onGuidanceUpdate(GuidanceUpdate("Tournez à droite", 150, 1200, 480))

        val guiding = session.state.value as VoiceNavUiState.Guiding
        assertEquals("Tournez à droite", guiding.update.instruction)
        assertTrue(spoken.isEmpty())

        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf("Arrivée à destination"), spoken)
    }

    @Test
    fun `mises a jour hors guidage ignorees`() = runBlocking {
        val session = session()

        session.onGuidanceUpdate(GuidanceUpdate("Tournez à droite", 150, 1200, 480))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `bouton arreter ferme le guidage avec annonce`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        spoken.clear()

        session.stopGuidance()

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf("Guidage arrêté"), spoken)
    }

    @Test
    fun `ordre stop dicte consomme en session seulement`() = runBlocking {
        val session = session()

        assertFalse(session.onVoiceTranscript("arrête le guidage"))
        assertTrue(spoken.isEmpty())

        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        assertTrue(session.onVoiceTranscript("arrête le guidage"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue("Guidage arrêté" in spoken)
    }

    @Test
    fun `ordre stop en confirmation ferme sans lancer`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher)
        session.onVoiceTranscript("va à la gare")

        assertTrue(session.onVoiceTranscript("stop la navigation"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(launcher.opened.isEmpty())
    }

    @Test
    fun `collaborateur qui jette ne fait pas lever`() = runBlocking {
        val throwing = VoiceNavSession(
            launcher = FakeLauncher(),
            guidanceEnabled = { true },
            announcer = NavigationAnnouncer({ Settings.Default }) { _, _ -> },
            voice = VoiceNavAnnouncer({ Settings.Default }) { _, _ -> },
            routeLoader = { throw IllegalStateException("panne") },
            router = NavigationRouter(parse = { throw IllegalStateException("panne") }),
        )

        assertFalse(throwing.onVoiceTranscript("va à la gare"))
        assertEquals(VoiceNavUiState.Idle, throwing.state.value)
    }
}
