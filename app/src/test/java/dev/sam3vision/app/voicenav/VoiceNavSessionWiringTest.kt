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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coutures intégrateur : parseurs hors routeur, moteur embarqué, repos. */
class VoiceNavSessionWiringTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)

    private class WiredLauncher(var fail: Boolean = false) : NavigationLauncher {
        val launched = mutableListOf<NavDestination>()
        val modes = mutableListOf<TravelMode>()

        override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
            launched += destination
            modes += mode
            return if (fail) Result.failure(IllegalStateException("refusé"))
            else Result.success(NavRoute.MapsApp)
        }

        override fun openPlace(query: String): Result<NavRoute> = Result.success(NavRoute.Browser)
    }

    private fun session(
        launcher: WiredLauncher = WiredLauncher(),
        extra: List<(String) -> String?> = emptyList(),
        engine: (suspend (String, RouteSummary?) -> Boolean)? = null,
        idled: () -> Unit = {},
        autoStart: Boolean = false,
    ) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { true },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = { Result.success(summary) },
        router = NavigationRouter(),
        extraParsers = extra,
        engineStart = engine,
        onSessionIdle = idled,
        autoStartOnVoice = autoStart,
    )

    @Test
    fun `forme hors routeur consommee et normalisee`() = runBlocking {
        val wired = session(extra = listOf({ s -> s.removePrefix("direction ") }))

        assertTrue(wired.onVoiceTranscript("direction   gare de Lyon !"))

        val state = wired.state.value as VoiceNavUiState.Confirming
        assertEquals("gare de Lyon", state.destination)
    }

    @Test
    fun `le routeur garde la priorite sur les parseurs`() = runBlocking {
        val wired = session(extra = listOf({ "leurre" }))

        assertTrue(wired.onVoiceTranscript("emmène-moi à la gare"))

        val state = wired.state.value as VoiceNavUiState.Confirming
        assertEquals("la gare", state.destination)
    }

    @Test
    fun `moteur demarre sans Maps ni annonce session`() = runBlocking {
        val launcher = WiredLauncher()
        val wired = session(launcher = launcher, engine = { _, _ -> true })

        assertTrue(wired.onVoiceTranscript("emmène-moi à la gare"))
        wired.confirmDestination()

        assertTrue(wired.state.value is VoiceNavUiState.Guiding)
        assertTrue(launcher.launched.isEmpty())
        assertFalse(spoken.any { it.startsWith("Navigation vers") })
    }

    @Test
    fun `moteur refuse ou en panne garde la confirmation avec motif`() = runBlocking {
        val refused = WiredLauncher()
        val sessionRefused = session(launcher = refused, engine = { _, _ -> false })
        assertTrue(sessionRefused.onVoiceTranscript("emmène-moi à la gare"))
        sessionRefused.confirmDestination()
        val refusedState = sessionRefused.state.value as VoiceNavUiState.Confirming
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, refusedState.error)
        assertTrue(refused.launched.isEmpty())

        val broken = WiredLauncher()
        val sessionBroken = session(launcher = broken, engine = { _, _ -> throw IllegalStateException("panne") })
        assertTrue(sessionBroken.onVoiceTranscript("emmène-moi à la gare"))
        sessionBroken.confirmDestination()
        val brokenState = sessionBroken.state.value as VoiceNavUiState.Confirming
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, brokenState.error)
        assertTrue(broken.launched.isEmpty())
    }

    @Test
    fun `le repos notifie le pilote`() = runBlocking {
        var idles = 0
        val wired = session(idled = { idles++ })

        assertTrue(wired.onVoiceTranscript("emmène-moi à la gare"))
        wired.cancelConfirmation()
        assertEquals(1, idles)
        val driving = session(idled = { idles++ }, engine = { _, _ -> true })
        assertTrue(driving.onVoiceTranscript("emmène-moi à la gare"))
        driving.confirmDestination()
        driving.stopGuidance()
        assertEquals(2, idles)
    }

    @Test
    fun `auto-start forme hors routeur demarre sans confirmation`() = runBlocking {
        val launcher = WiredLauncher()
        val wired = session(
            launcher = launcher,
            extra = listOf({ s -> s.removePrefix("direction ") }),
            engine = { _, _ -> true },
            autoStart = true,
        )

        assertTrue(wired.onVoiceTranscript("direction gare de Lyon"))

        val state = wired.state.value as VoiceNavUiState.Guiding
        assertEquals("gare de Lyon", state.destination)
        assertTrue(launcher.launched.isEmpty())
    }

    @Test
    fun `auto-start moteur refuse annonce sans app externe`() = runBlocking {
        val launcher = WiredLauncher()
        val wired = session(
            launcher = launcher,
            engine = { _, _ -> false },
            autoStart = true,
        )

        assertTrue(wired.onVoiceTranscript("emmène-moi à la gare"))

        assertEquals(VoiceNavUiState.Idle, wired.state.value)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `auto-start garde le routeur prioritaire sur les parseurs`() = runBlocking {
        val wired = session(
            extra = listOf({ "leurre" }),
            engine = { _, _ -> true },
            autoStart = true,
        )

        assertTrue(wired.onVoiceTranscript("emmène-moi à la gare"))

        val state = wired.state.value as VoiceNavUiState.Guiding
        assertEquals("la gare", state.destination)
    }
}
