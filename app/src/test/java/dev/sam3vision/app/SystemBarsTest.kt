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

    @Test
    fun `isee scene and live hide the status bar over the camera`() {
        assertTrue(hidesStatusBar(Route.Isee))
        assertTrue(hidesStatusBar(Route.Live))
    }

    @Test
    fun `other screens keep the status bar`() {
        assertFalse(hidesStatusBar(Route.Settings))
        assertFalse(hidesStatusBar(Route.Caregiver))
        assertFalse(hidesStatusBar(Route.Onboarding))
        assertFalse(hidesStatusBar(Route.Capture))
        assertFalse(hidesStatusBar(Route.Analysis))
        assertFalse(hidesStatusBar(Route.Chat))
        assertFalse(hidesStatusBar(Route.Bench))
        assertFalse(hidesStatusBar(Route.VoiceNav))
        assertFalse(hidesStatusBar(Route.Tap(autoStart = false)))
        assertFalse(hidesStatusBar(Route.Tap(autoStart = true)))
    }
}
