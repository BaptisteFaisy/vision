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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saisie clavier de secours : normalisation et soumission via la session existante. */
class DestinationTextFieldTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)

    private class TextLauncher : NavigationLauncher {
        override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> =
            Result.success(NavRoute.MapsApp)
        override fun openPlace(query: String): Result<NavRoute> = Result.success(NavRoute.Browser)
    }

    private fun session() = VoiceNavSession(
        launcher = TextLauncher(),
        guidanceEnabled = { true },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = { Result.success(summary) },
        router = NavigationRouter(),
    )

    @Test
    fun `texte simple prefixe en go to`() {
        assertEquals("go to gare de Lyon", typedDestinationQuery("gare de Lyon"))
    }

    @Test
    fun `espaces superflus reduits`() {
        assertEquals("go to la tour Eiffel", typedDestinationQuery("  la   tour  Eiffel  "))
    }

    @Test
    fun `ponctuation des bords retiree interieure gardee`() {
        assertEquals("go to Saint-Germain-en-Laye", typedDestinationQuery("…Saint-Germain-en-Laye !"))
        assertEquals("go to 12, rue de la Paix", typedDestinationQuery("12, rue de la Paix."))
    }

    @Test
    fun `vide blanc et ponctuation seule refuses`() {
        assertNull(typedDestinationQuery(""))
        assertNull(typedDestinationQuery("   "))
        assertNull(typedDestinationQuery("… !?"))
    }

    @Test
    fun `saisie soumise ouvre la confirmation comme une dictee`() = runBlocking {
        val nav = session()

        assertTrue(nav.onVoiceTranscript(typedDestinationQuery("  gare de Lyon ! ")!!))

        val state = nav.state.value as VoiceNavUiState.Confirming
        assertEquals("gare de Lyon", state.destination)
        assertEquals(
            listOf("Do you really want to go to gare de Lyon? Travel time: 15 minutes. Answer yes or no."),
            spoken,
        )
    }

    @Test
    fun `une saisie remplace la confirmation en cours`() = runBlocking {
        val nav = session()
        nav.onVoiceTranscript("va à la gare")
        spoken.clear()

        assertTrue(nav.onVoiceTranscript(typedDestinationQuery("tour Eiffel")!!))

        val state = nav.state.value as VoiceNavUiState.Confirming
        assertEquals("tour Eiffel", state.destination)
        assertEquals(
            listOf("Do you really want to go to tour Eiffel? Travel time: 15 minutes. Answer yes or no."),
            spoken,
        )
    }
}
