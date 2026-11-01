package dev.sam3vision.app.voicenav

import dev.sam3vision.app.navigation.NavDestination
import dev.sam3vision.app.navigation.NavRoute
import dev.sam3vision.app.navigation.NavigationLauncher
import dev.sam3vision.app.navigation.TravelMode
import dev.sam3vision.app.speech.NavigationAnnouncer
import dev.sam3vision.app.speech.NavigationPhrases
import dev.sam3vision.app.speech.VoiceNavAnnouncer
import dev.sam3vision.core.nav.ManeuverAction
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavManeuver
import dev.sam3vision.core.nav.PeriodicGuidance
import dev.sam3vision.core.nav.PeriodicTuning
import dev.sam3vision.core.nav.RouteSummary
import dev.sam3vision.core.nav.WalkDirective
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.usecase.NavigationRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Doublure du lanceur externe : la session SPEC v4 ne doit plus jamais l'appeler. */
private class GpsVoiceLauncher : NavigationLauncher {
    val launched = mutableListOf<NavDestination>()
    val opened = mutableListOf<String>()

    override fun launch(destination: NavDestination, mode: TravelMode): Result<NavRoute> {
        launched += destination
        return Result.success(NavRoute.MapsApp)
    }

    override fun openPlace(query: String): Result<NavRoute> {
        opened += query
        return Result.success(NavRoute.Browser)
    }
}

/**
 * Voix GPS dédiée SPEC v4 (JUnit seul, pur JVM) : rappel 15 s (dernière
 * consigne + distance restante), garde voix-route sur le lecteur réel,
 * aucun repli Google Maps.
 */
class VoiceNavGpsVoiceTest {

    private val spoken = mutableListOf<String>()
    private val summary = RouteSummary("la gare", "Place Louis-Armand, Paris", 2450, 900)
    private val questionTour =
        "Do you really want to go to la tour Eiffel? Travel time: 15 minutes. Answer yes or no."

    private fun session(
        launcher: GpsVoiceLauncher = GpsVoiceLauncher(),
        guidance: Boolean = true,
        autoStart: Boolean = false,
    ) = VoiceNavSession(
        launcher = launcher,
        guidanceEnabled = { guidance },
        announcer = NavigationAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        voice = VoiceNavAnnouncer({ Settings.Default }) { _, text -> spoken += text },
        routeLoader = { Result.success(summary) },
        router = NavigationRouter(),
        engineStart = { _, _ -> true },
        autoStartOnVoice = autoStart,
    )

    @Test
    fun `le rappel repete la consigne et la distance restante`() {
        assertEquals(
            "Turn right, rue de la Paix. 450 meters remaining.",
            gpsReminderPhrase("Turn right, rue de la Paix", 450),
        )
    }

    @Test
    fun `le rappel rogne le point et couvre le singulier`() {
        assertEquals(
            "Destination in 200 meters. 1 meter remaining.",
            gpsReminderPhrase("Destination in 200 meters.", 1),
        )
    }

    @Test
    fun `le rappel sans consigne dit tout droit`() {
        assertEquals(
            "Continue straight ahead. 120 meters remaining.",
            gpsReminderPhrase("   ", 120),
        )
    }

    @Test
    fun `le rappel bat a 15 secondes`() {
        assertEquals(15_000L, GPS_REMINDER_MS)
        val recall = PeriodicGuidance(PeriodicTuning(repeatMs = GPS_REMINDER_MS))
        val target = NavManeuver(ManeuverAction.Continue, 48.85090, 2.35000)
        fun fix(atMs: Long) = NavFix(lat = 48.85000, lon = 2.35000, accM = 8f, atMs = atMs)

        assertEquals(WalkDirective.Straight, recall.onFix(fix(0L), target))
        assertNull(recall.onFix(fix(5_000L), target))
        assertNull(recall.onFix(fix(14_999L), target))
        assertEquals(WalkDirective.Straight, recall.onFix(fix(15_000L), target))
    }

    @Test
    fun `voix reseau le garde lit le lecteur reel pas le tts`() {
        assertTrue(routeSpeechActive(networkWired = true, readerBusy = true, speakerActive = false))
        assertFalse(routeSpeechActive(networkWired = true, readerBusy = false, speakerActive = false))
    }

    @Test
    fun `voix systeme le garde lit le haut-parleur local`() {
        assertTrue(routeSpeechActive(networkWired = false, readerBusy = false, speakerActive = true))
        assertFalse(routeSpeechActive(networkWired = false, readerBusy = true, speakerActive = false))
    }

    @Test
    fun `guidage eteint manuel n'ouvre plus Maps`() = runBlocking {
        val launcher = GpsVoiceLauncher()
        val session = session(launcher, guidance = false)
        session.onVoiceTranscript("va à la tour Eiffel")

        session.confirmDestination()

        assertTrue(launcher.launched.isEmpty())
        assertTrue(launcher.opened.isEmpty())
        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf(questionTour, NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }

    @Test
    fun `guidage eteint voix n'ouvre plus Maps`() = runBlocking {
        val launcher = GpsVoiceLauncher()
        val session = session(launcher, guidance = false, autoStart = true)

        assertTrue(session.onVoiceTranscript("va à la tour Eiffel"))

        assertTrue(launcher.launched.isEmpty())
        assertTrue(launcher.opened.isEmpty())
        assertEquals(VoiceNavUiState.Idle, session.state.value)
        assertEquals(listOf(NavigationPhrases.GUIDANCE_UNAVAILABLE), spoken)
    }
}
