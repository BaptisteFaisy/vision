package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doublure artisanale du lanceur : enregistre les appels. */
private class ArrivedLauncher : NavigationLauncher {
    val launched = mutableListOf<NavDestination>()

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        launched += destination
        return Result.success(NavRoute.MapsApp)
    }

    override fun openPlace(query: String): Result<NavRoute> = Result.success(NavRoute.Browser)
}

/** Écran d'arrivée : état, annonce unique, fermeture, ordres sur place. */
class VoiceNavSessionArrivedTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)
    private var idled = 0

    private fun session(launcher: ArrivedLauncher = ArrivedLauncher()) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { true },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = { Result.success(summary) },
        router = NavigationRouter(),
        onSessionIdle = { idled++ },
    )

    private suspend fun guiding(): VoiceNavSession {
        val session = session()
        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        return session
    }

    @Test
    fun `arrivee ouvre l'ecran d'arrivee avec annonce unique`() = runBlocking {
        val session = guiding()
        spoken.clear()

        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))

        val state = session.state.value as VoiceNavUiState.Arrived
        assertEquals("la gare", state.destination)
        assertEquals(summary, state.summary)
        assertEquals(listOf("Arrivée à destination"), spoken)
        assertEquals(0, idled)

        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))

        assertTrue(session.state.value is VoiceNavUiState.Arrived)
        assertEquals(listOf("Arrivée à destination"), spoken)
    }

    @Test
    fun `mises a jour apres l'arrivee ignorees`() = runBlocking {
        val session = guiding()
        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))
        spoken.clear()

        session.onGuidanceUpdate(GuidanceUpdate("Tournez à droite", 150, 1200, 480))

        assertTrue(session.state.value is VoiceNavUiState.Arrived)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `fermer l'arrivee revient au repos sans annonce et coupe le moteur`() = runBlocking {
        val session = guiding()
        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))
        spoken.clear()
        idled = 0

        session.dismissArrived()

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
        assertEquals(1, idled)
    }

    @Test
    fun `ordre stop dicte sur l'arrivee ferme sans annonce`() = runBlocking {
        val session = guiding()
        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))
        spoken.clear()

        assertTrue(session.onVoiceTranscript("arrête le guidage"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `confirmer sur l'arrivee ne fait rien`() = runBlocking {
        val launcher = ArrivedLauncher()
        val session = session(launcher)
        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))
        launcher.launched.clear()
        spoken.clear()

        session.confirmDestination()

        assertTrue(session.state.value is VoiceNavUiState.Arrived)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `nouvelle destination depuis l'arrivee rouvre la confirmation`() = runBlocking {
        val session = guiding()
        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))

        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals("la tour Eiffel", state.destination)
    }
}
