package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doublure artisanale du lanceur : enregistre les appels, échec programmable. */
private class AutoStartLauncher(var fail: Boolean = false) : NavigationLauncher {
    val launched = mutableListOf<NavDestination>()
    val modes = mutableListOf<TravelMode>()
    val opened = mutableListOf<String>()

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        launched += destination
        modes += mode
        return if (fail) Result.failure(IllegalStateException("refusé")) else Result.success(NavRoute.MapsApp)
    }

    override fun openPlace(query: String): Result<NavRoute> {
        opened += query
        return if (fail) Result.failure(IllegalStateException("refusé")) else Result.success(NavRoute.Browser)
    }
}

/** Démarrage direct sans confirmation : chargement, moteur, indisponibilité. */
class VoiceNavSessionAutoStartTest {

    private val spoken = mutableListOf<String>()

    /** Horodatage des ordres « désactive / active le GPS » (persistance). */
    private val gpsFlags = mutableListOf<Boolean>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)
    private val questionGare =
        "Do you really want to go to la gare? Travel time: 15 minutes. Answer yes or no."
    private val questionTour =
        "Do you really want to go to la tour Eiffel? Travel time: 15 minutes. Answer yes or no."
    private val questionTourNoTime = "Do you really want to go to la tour Eiffel? Answer yes or no."

    private fun session(
        launcher: AutoStartLauncher = AutoStartLauncher(),
        guidance: Boolean = true,
        loader: RouteLoader = { Result.success(summary) },
        engine: (suspend (String, RouteSummary?) -> Boolean)? = { _, _ -> true },
        autoStart: Boolean = false,
    ) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { guidance },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = loader,
        router = NavigationRouter(),
        engineStart = engine,
        autoStartOnVoice = autoStart,
        onGpsDisabled = { gpsFlags += false },
        onGpsEnabled = { gpsFlags += true },
    )

    @Test
    fun `auto-start moteur demarre sans confirmation`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(launcher, autoStart = true)

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))

        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals("la gare", state.destination)
        assertTrue(launcher.launched.isEmpty())
        // Une seule voix : le moteur dit « Navigating to X », pas la session.
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `auto-start affiche le chargement puis demarre sans question`() = runBlocking {
        val gate = CompletableDeferred<Result<RouteSummary>>()
        val launcher = AutoStartLauncher()
        val session = session(launcher, loader = { gate.await() }, autoStart = true)
        val starting = async { session.onVoiceTranscript("va à la gare") }
        delay(50)

        val loading = session.state.value as VoiceNavUiState.Confirming
        assertEquals("la gare", loading.destination)
        assertTrue(loading.loading)

        gate.complete(Result.success(summary))
        assertTrue(starting.await())

        assertTrue(session.state.value is VoiceNavUiState.Guiding)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `auto-start charge le résumé pour l'état initial, jamais des zéros`() = runBlocking {
        var askedSummary: RouteSummary? = null
        val launcher = AutoStartLauncher()
        val session = session(
            launcher,
            engine = { _, loaded -> askedSummary = loaded; true },
            autoStart = true,
        )

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))

        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals(summary, askedSummary)
        assertEquals(summary, state.summary)
        assertEquals(2450L, state.update.distanceToManeuverMeters)
        assertEquals(2450L, state.update.remainingDistanceMeters)
        assertEquals(900L, state.update.remainingDurationSeconds)
    }

    @Test
    fun `auto-start résumé en échec affiche le calcul en cours`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(
            launcher,
            loader = { Result.failure(IllegalStateException("panne")) },
            autoStart = true,
        )

        assertTrue(session.onVoiceTranscript("va à la gare"))

        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals(null, state.summary)
        assertEquals("Calculating the route to la gare…", state.update.instruction)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `auto-start sans moteur annonce indisponible sans app externe`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(launcher, engine = null, autoStart = true)

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `auto-start moteur en panne annonce indisponible sans app externe`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(
            launcher,
            engine = { _, _ -> throw IllegalStateException("panne") },
            autoStart = true,
        )

        assertTrue(session.onVoiceTranscript("va à la gare"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `auto-start guidage eteint ouvre la fiche lieu`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(launcher, guidance = false, autoStart = true)

        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf("la tour Eiffel"), launcher.opened)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf("Here is the page for la tour Eiffel"), spoken)
    }

    @Test
    fun `auto-start sans moteur annonce sans ecran`() = runBlocking {
        val launcher = AutoStartLauncher()
        val session = session(launcher, engine = null, autoStart = true)

        assertTrue(session.onVoiceTranscript("va à la gare"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `auto-start echec perime ne tue pas le demarrage suivant`() = runBlocking {
        val gate = CompletableDeferred<Boolean>()
        val launcher = AutoStartLauncher()
        val session = session(
            launcher,
            engine = { destination, _ -> if (destination == "la gare") gate.await() else true },
            autoStart = true,
        )
        val first = async { session.onVoiceTranscript("va à la gare") }
        delay(50)
        assertTrue((session.state.value as VoiceNavUiState.Confirming).loading)

        // Deuxième dictée pendant que le moteur de la première travaille :
        // elle démarre aussitôt et son guidage doit survivre à l'échec tardif
        // de la première (ni retour au repos, ni annonce périmée).
        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))
        assertTrue(session.state.value is VoiceNavUiState.Guiding)

        gate.complete(false)
        assertTrue(first.await())

        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals("la tour Eiffel", state.destination)
        assertTrue(spoken.none { it == NavigationPhrases.GUIDANCE_UNAVAILABLE })
    }
}
