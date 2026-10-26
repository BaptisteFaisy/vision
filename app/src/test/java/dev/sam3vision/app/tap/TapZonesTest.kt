package dev.sam3vision.app.tap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zones exclues : barres système (haut/bas) et bords de l'écran. */
class TapZonesTest {

    @Test
    fun `un tap dans la barre d etat est exclu`() {
        assertTrue(isExcludedTapZone(y = 40f, topInsetPx = 96, bottomInsetPx = 120, viewHeightPx = 2400))
    }

    @Test
    fun `un tap dans la barre de navigation est exclu`() {
        assertTrue(isExcludedTapZone(y = 2350f, topInsetPx = 96, bottomInsetPx = 120, viewHeightPx = 2400))
    }

    @Test
    fun `un tap au centre declenche la video`() {
        assertFalse(isExcludedTapZone(y = 1200f, topInsetPx = 96, bottomInsetPx = 120, viewHeightPx = 2400))
    }

    @Test
    fun `les bords gauche et droit restent au systeme`() {
        assertTrue(isExcludedSideZone(x = 4f, slopPx = 24f, viewWidthPx = 1080))
        assertTrue(isExcludedSideZone(x = 1078f, slopPx = 24f, viewWidthPx = 1080))
        assertFalse(isExcludedSideZone(x = 540f, slopPx = 24f, viewWidthPx = 1080))
    }
}

/** Le relâchement est le déclencheur : ni appui long, ni glissement, ni durée >= seuil. */
class TapReleaseTest {

    @Test
    fun `un relachement court declenche l action`() {
        assertTrue(isShortTapRelease(longPressed = false, dragged = false, pressDurationMs = 120L, longPressTimeoutMs = 500L))
        assertTrue(isShortTapRelease(longPressed = false, dragged = false, pressDurationMs = 499L, longPressTimeoutMs = 500L))
    }

    @Test
    fun `appui long et glissement ne declenchent rien`() {
        assertFalse(isShortTapRelease(longPressed = true, dragged = false, pressDurationMs = 120L, longPressTimeoutMs = 500L))
        assertFalse(isShortTapRelease(longPressed = false, dragged = true, pressDurationMs = 120L, longPressTimeoutMs = 500L))
        assertFalse(isShortTapRelease(longPressed = false, dragged = false, pressDurationMs = 800L, longPressTimeoutMs = 500L))
    }
}
