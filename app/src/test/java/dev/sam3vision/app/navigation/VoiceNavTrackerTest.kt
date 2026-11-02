package dev.sam3vision.app.navigation

import dev.sam3vision.app.NO_ACCURACY_M
import dev.sam3vision.core.nav.NavFix
import dev.sam3vision.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surface pure du suiveur (JUnit seul, sans Android ni mock) : [navFixOf]
 * convertit une position brute en fix moteur, [shouldResume] décide la
 * reprise d'écoute. La classe [VoiceNavTracker] (LocationManager réel)
 * n'est pas instanciée ici.
 */
class VoiceNavTrackerTest {

    @Test
    fun `position brute devient fix moteur`() {
        val fix = navFixOf(48.85, 2.35, 8f, hasAccuracy = true, atMs = 1_000L)
        assertEquals(48.85, fix.lat, 0.0)
        assertEquals(2.35, fix.lon, 0.0)
        assertEquals(8f, fix.accM, 0f)
        assertEquals(1_000L, fix.atMs)
    }

    @Test
    fun `sans precision annoncee repli partage`() {
        val fix = navFixOf(48.85, 2.35, 0f, hasAccuracy = false, atMs = 2_000L)
        assertEquals(NO_ACCURACY_M, fix.accM, 0f)
    }

    @Test
    fun `reprise quand le flux est coupe en guidage`() {
        assertTrue(shouldResume(listening = false, NavState.Guiding))
    }

    @Test
    fun `reprise quand le flux est coupe en ecart`() {
        assertTrue(shouldResume(listening = false, NavState.OffRoute))
    }

    @Test
    fun `pas de reprise quand l ecoute tourne deja`() {
        assertFalse(shouldResume(listening = true, NavState.Guiding))
    }

    @Test
    fun `pas de reprise au repos ni apres l arrivee`() {
        assertFalse(shouldResume(listening = false, NavState.Idle))
        assertFalse(shouldResume(listening = false, NavState.Finished))
    }

    @Test
    fun `cap suivi repris quand le gps marche`() {
        val fix = navFixOf(48.85, 2.35, 8f, hasAccuracy = true, atMs = 1_000L, headingDeg = 90f)

        assertTrue(fix.headingDeg == 90f)
    }

    @Test
    fun `sans cap le fix reste muet sur la direction`() {
        val fix = navFixOf(48.85, 2.35, 8f, hasAccuracy = true, atMs = 1_000L)

        assertNull(fix.headingDeg)
    }

    @Test
    fun `amorce le plus frais des fixes connus`() {
        val gps = NavFix(48.85, 2.35, 8f, atMs = 2_000L)
        val net = NavFix(48.86, 2.36, 20f, atMs = 1_000L)

        assertEquals(gps, seedFixOf(gps, net))
        assertEquals(gps, seedFixOf(net, gps))
    }

    @Test
    fun `amorce ignore l inexploitable meme plus frais`() {
        val stale = NavFix(48.85, 2.35, 8f, atMs = 1_000L)
        val coarse = NavFix(48.86, 2.36, 0f, atMs = 2_000L)

        assertEquals(stale, seedFixOf(stale, coarse))
    }

    @Test
    fun `amorce nulle sans fix connu ni exploitable`() {
        assertNull(seedFixOf(null, null))
        assertNull(seedFixOf(NavFix(48.85, 2.35, 0f, atMs = 1_000L), null))
    }

    @Test
    fun `egalite d age le gps gagne comme le partage`() {
        val gps = NavFix(48.85, 2.35, 8f, atMs = 1_000L)
        val net = NavFix(48.86, 2.36, 20f, atMs = 1_000L)

        assertEquals(gps, seedFixOf(gps, net))
    }
}
