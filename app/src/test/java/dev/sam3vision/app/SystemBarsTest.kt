package dev.sam3vision.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le bandeau système du haut est blanc sur les écrans clairs (fond blanc),
 * sombre partout ailleurs (scène iSee, écrans sombres, caméra).
 */
class SystemBarsTest {

    @Test
    fun `light screens get the white status band`() {
        assertTrue(whiteStatusBar(Route.Settings))
        assertTrue(whiteStatusBar(Route.Caregiver))
        assertTrue(whiteStatusBar(Route.Onboarding))
    }

    @Test
    fun `scene and dark screens keep the dark status band`() {
        assertFalse(whiteStatusBar(Route.Isee))
        assertFalse(whiteStatusBar(Route.Capture))
        assertFalse(whiteStatusBar(Route.Analysis))
        assertFalse(whiteStatusBar(Route.Chat))
        assertFalse(whiteStatusBar(Route.Live))
        assertFalse(whiteStatusBar(Route.Bench))
        assertFalse(whiteStatusBar(Route.VoiceNav))
        assertFalse(whiteStatusBar(Route.Tap(autoStart = false)))
        assertFalse(whiteStatusBar(Route.Tap(autoStart = true)))
    }
}
