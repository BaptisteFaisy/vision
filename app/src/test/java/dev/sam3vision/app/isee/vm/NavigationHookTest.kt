package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.IseeUiState
import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
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

class NavigationHookTest {

    private val spoken = mutableListOf<String>()
    private var state = IseeUiState()

    private fun recordSpoken(text: String) {
        spoken += text
    }

    private fun hook(
        launcher: FakeLauncher = FakeLauncher(),
        guidance: Boolean = true,
        router: NavigationRouter = NavigationRouter(),
    ) = NavigationHook(
        router = router,
        launcher = launcher,
        guidanceEnabled = { guidance },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> recordSpoken(text) },
        update = { fn -> state = fn(state) },
    )

    @Test
    fun `sans intention rien n'est lance ni dit`() = runBlocking {
        val launcher = FakeLauncher()
        val consumed = hook(launcher).onTranscript("quelle heure est-il ?")

        assertFalse(consumed)
        assertTrue(launcher.launched.isEmpty())
        assertTrue(launcher.opened.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `blanc ignore sans rien toucher`() = runBlocking {
        val launcher = FakeLauncher()

        assertFalse(hook(launcher).onTranscript("   "))
        assertTrue(launcher.launched.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `guidage lance annonce et statut efface`() = runBlocking {
        val launcher = FakeLauncher()
        state = state.copy(status = "ancien")

        val consumed = hook(launcher).onTranscript("emmène-moi à la gare")

        assertTrue(consumed)
        assertEquals(listOf(NavDestination.Query("la gare")), launcher.launched)
        assertEquals(listOf(TravelMode.WALK), launcher.modes)
        assertTrue(launcher.opened.isEmpty())
        assertEquals(listOf("Going to la gare"), spoken)
        assertEquals(null, state.status)
    }

    @Test
    fun `guidage coupe ouvre la fiche lieu`() = runBlocking {
        val launcher = FakeLauncher()

        val consumed = hook(launcher, guidance = false).onTranscript("aller à Lyon")

        assertTrue(consumed)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf("Lyon"), launcher.opened)
        assertEquals(listOf("Going to Lyon"), spoken)
    }

    @Test
    fun `echec annonce et statut pose`() = runBlocking {
        val launcher = FakeLauncher(fail = true)

        val consumed = hook(launcher).onTranscript("aller à Lyon")

        assertTrue(consumed)
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, state.status)
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `direction X hors routeur ouvre Maps au lieu du LLM`() = runBlocking {
        val launcher = FakeLauncher()

        assertTrue(hook(launcher).onTranscript("direction gare de Lyon"))
        assertEquals(listOf(NavDestination.Query("gare de Lyon")), launcher.launched)
        assertEquals(listOf("Going to gare de Lyon"), spoken)
    }

    @Test
    fun `routeur qui jette = pas d'intention`() = runBlocking {
        val launcher = FakeLauncher()
        val throwing = NavigationRouter(parse = { throw IllegalStateException("boom") })

        assertFalse(hook(launcher, router = throwing).onTranscript("aller à Lyon"))
        assertTrue(launcher.launched.isEmpty())
        assertTrue(spoken.isEmpty())
    }
}
