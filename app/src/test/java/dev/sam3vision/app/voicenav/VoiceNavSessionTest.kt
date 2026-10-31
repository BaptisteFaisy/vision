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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

class VoiceNavSessionTest {

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
        launcher: FakeLauncher = FakeLauncher(),
        guidance: Boolean = true,
        loader: RouteLoader = { Result.success(summary) },
        engine: (suspend (String, RouteSummary?) -> Boolean)? = { _, _ -> true },
        autoStart: Boolean = false,
        remembered: MutableList<String?>? = null,
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
        onActiveDestination = { remembered?.add(it) },
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
        assertEquals(listOf(questionGare), spoken)
    }

    @Test
    fun `sacre-coeur dicte ouvre la confirmation sur son adresse`() = runBlocking {
        val session = session()
        assertTrue(session.onVoiceTranscript("I want to go to the Sacré Coeur"))
        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals("35 Rue du Chevalier de la Barre", state.destination)
        assertEquals(listOf("Do you really want to go to 35 Rue du Chevalier de la Barre? Travel time: 15 minutes. Answer yes or no."), spoken)
    }

    @Test
    fun `chargeur en echec montre l'adresse seule`() = runBlocking {
        val session = session(loader = { Result.failure(IllegalStateException("moteur coupé")) })

        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals("la tour Eiffel", state.destination)
        assertEquals(null, state.summary)
        assertEquals("moteur coupé", state.error)
        assertEquals(listOf(questionTourNoTime), spoken)
    }

    @Test
    fun `confirmer avec moteur demarre le guidage sans Maps`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher)
        session.onVoiceTranscript("naviguer vers la gare")

        session.confirmDestination()

        val state = session.state.value as VoiceNavUiState.Guiding
        assertEquals("la gare", state.destination)
        assertEquals("Heading to la gare", state.update.instruction)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(questionGare), spoken)
    }

    @Test
    fun `confirmer sans moteur affiche le motif sans app externe`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher, engine = null)
        session.onVoiceTranscript("va à la gare")

        session.confirmDestination()

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, state.error)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(
            listOf(questionGare, NavigationPhrases.GUIDANCE_UNAVAILABLE),
            spoken,
        )
    }

    @Test
    fun `confirmer sans resume part quand meme`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher, loader = UnavailableRouteLoader)
        session.onVoiceTranscript("va à la gare")

        session.confirmDestination()

        assertTrue(launcher.launched.isEmpty())
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
            listOf(questionTour, "Here is the page for la tour Eiffel"),
            spoken,
        )
    }

    @Test
    fun `moteur absent garde la confirmation avec motif`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher, engine = null)
        session.onVoiceTranscript("va à la gare")

        session.confirmDestination()

        val state = session.state.value as VoiceNavUiState.Confirming
        assertEquals(NavigationPhrases.GUIDANCE_UNAVAILABLE, state.error)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(
            listOf(questionGare, NavigationPhrases.GUIDANCE_UNAVAILABLE),
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
    fun `yes vocal demarre le guidage depuis la confirmation`() = runBlocking {
        val launcher = FakeLauncher()
        val session = session(launcher)
        session.onVoiceTranscript("va à la gare")

        assertTrue(session.onVoiceTranscript("yes"))

        assertTrue(session.state.value is VoiceNavUiState.Guiding)
        assertTrue(launcher.launched.isEmpty())
        assertEquals(listOf(questionGare), spoken)
    }

    @Test
    fun `no vocal annule avec accuse`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("va à la gare")

        assertTrue(session.onVoiceTranscript("non"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf(questionGare, "Cancelled."), spoken)
    }

    @Test
    fun `oui hors confirmation n'est pas consomme`() = runBlocking {
        val session = session()

        assertFalse(session.onVoiceTranscript("oui"))
        assertFalse(session.onVoiceTranscript("no"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `yes pendant le chargement fait patienter`() = runBlocking {
        val gate = CompletableDeferred<Result<RouteSummary>>()
        val session = session(loader = { gate.await() })
        val opening = async { session.onVoiceTranscript("va à la gare") }
        delay(50)

        assertTrue(session.onVoiceTranscript("yes"))
        assertEquals(listOf("Your route is still loading, one moment."), spoken)

        gate.complete(Result.success(summary))
        assertTrue(opening.await())
        assertEquals(
            listOf("Your route is still loading, one moment.", questionGare),
            spoken,
        )
        assertTrue(session.state.value is VoiceNavUiState.Confirming)
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

        val arrived = session.state.value as VoiceNavUiState.Arrived
        assertEquals("la gare", arrived.destination)
        assertEquals(listOf("Arrived at destination"), spoken)

        session.dismissArrived()

        assertEquals(VoiceNavUiState.Idle, session.state.value)
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
        assertEquals(listOf("Guidance stopped"), spoken)
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
        assertTrue("Guidance stopped" in spoken)
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
    fun `ordre desactive gps ferme tout et persiste`() = runBlocking {
        val session = session()
        session.onVoiceTranscript("va à la gare")
        session.confirmDestination()
        spoken.clear()

        assertTrue(session.onVoiceTranscript("désactive le GPS"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf(false), gpsFlags)
        assertEquals(listOf("GPS disabled"), spoken)

        // Même sans session ouverte : l'ordre demeure celui de la fonction.
        assertTrue(session.onVoiceTranscript("coupe le gps"))
        assertEquals(listOf(false, false), gpsFlags)
    }

    @Test
    fun `ordre active gps rallume sans lancer de guidage`() = runBlocking {
        val session = session()

        assertTrue(session.onVoiceTranscript("active le GPS"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf(true), gpsFlags)
        assertEquals(listOf("GPS enabled"), spoken)
    }

    @Test
    fun `ordres gps hors reconnus lisibles a travers la session`() = runBlocking {
        val session = session()

        // Accents et casse sans importance, objet « guidage / navigation » accepté.
        assertTrue(session.onVoiceTranscript("DÉSACTIVE LE GUIDAGE"))
        assertTrue(session.onVoiceTranscript("réactive la navigation"))

        assertEquals(listOf(false, true), gpsFlags)
    }

    @Test
    fun `question parlée sans ordre GPS pas confondue`() = runBlocking {
        val session = session()

        assertFalse(session.onVoiceTranscript("où est le GPS le plus proche ?"))
        assertTrue(gpsFlags.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `guidage demarre — la destination est rappelee puis oubliee a l'arret`() = runBlocking {
        val remembered = mutableListOf<String?>()
        val session = session(autoStart = true, remembered = remembered)

        assertTrue(session.onVoiceTranscript("va à la gare"))
        assertTrue(session.state.value is VoiceNavUiState.Guiding)
        assertEquals("la gare", remembered.last())

        session.stopGuidance()

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertNull(remembered.last())
    }

    @Test
    fun `confirmation seule — rien n'est rappele, le demarrage rappelle`() = runBlocking {
        val remembered = mutableListOf<String?>()
        val session = session(remembered = remembered)

        assertTrue(session.onVoiceTranscript("emmène-moi à la gare"))
        assertTrue("pas de destination hors guidage", remembered.isNotEmpty() && remembered.all { it == null })

        session.confirmDestination()

        assertEquals("la gare", remembered.last())
    }

    @Test
    fun `arrivee — la destination est oubliee`() = runBlocking {
        val remembered = mutableListOf<String?>()
        val session = session(remembered = remembered)
        session.onVoiceTranscript("emmène-moi à la gare")
        session.confirmDestination()
        assertEquals("la gare", remembered.last())

        session.onGuidanceUpdate(GuidanceUpdate("Arrivée", 0, 0, 0, arrived = true))

        assertTrue(session.state.value is VoiceNavUiState.Arrived)
        assertNull(remembered.last())
    }

    @Test
    fun `desactiver le GPS en guidage — la destination est oubliee`() = runBlocking {
        val remembered = mutableListOf<String?>()
        val session = session(autoStart = true, remembered = remembered)
        session.onVoiceTranscript("va à la gare")
        assertEquals("la gare", remembered.last())

        assertTrue(session.onVoiceTranscript("désactive le GPS"))

        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertNull(remembered.last())
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
            autoStartOnVoice = false,
        )

        assertFalse(throwing.onVoiceTranscript("va à la gare"))
        assertEquals(VoiceNavUiState.Idle, throwing.state.value)
    }
}
