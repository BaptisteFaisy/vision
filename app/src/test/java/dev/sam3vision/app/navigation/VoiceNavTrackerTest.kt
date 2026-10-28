package dev.sam3vision.app.navigation

import dev.sam3vision.app.NO_ACCURACY_M
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Surface pure du suiveur (JUnit seul, sans Android ni mock) : [navFixOf]
 * convertit une position brute en fix moteur. La classe [VoiceNavTracker]
 * (LocationManager réel) n'est pas instanciée ici.
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
}
