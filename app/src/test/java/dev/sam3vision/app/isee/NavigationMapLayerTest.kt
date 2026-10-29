package dev.sam3vision.app.isee

import dev.sam3vision.core.nav.NavFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Carte du mode navigation : quand elle s'efface (l'utilisateur parle) et le
 * cadre du schéma de repli.
 */
class NavigationMapLayerTest {

    private val fix = NavFix(lat = 48.8606, lon = 2.3376, accM = 8f, atMs = 0L)

    @Test
    fun `la carte reste visible au repos`() {
        assertFalse(navigationMapHidden(IseeUiState()))
    }

    @Test
    fun `la carte s'efface pendant que l'utilisateur parle`() {
        assertTrue(navigationMapHidden(IseeUiState(holding = true)))
        assertTrue(navigationMapHidden(IseeUiState(transcribing = true)))
    }

    @Test
    fun `iSee qui parle ne masque pas la carte`() {
        assertFalse(navigationMapHidden(IseeUiState(speakingEntryId = 1L, speakingText = "Bonjour")))
        assertFalse(navigationMapHidden(IseeUiState(thinking = true)))
    }

    @Test
    fun `le cadre de repli fait 200 m autour de la position`() {
        val bounds = viewBoundsOf(fix)

        assertTrue(bounds.maxLat > fix.lat && bounds.minLat < fix.lat)
        // 200 m en latitude ≈ 0,00180°.
        assertEquals(0.001797, bounds.maxLat - fix.lat, 1e-5)
    }

    @Test
    fun `sans position le cadre de repli reste projetable`() {
        val bounds = viewBoundsOf(null)

        assertEquals(bounds.minLat, bounds.maxLat, 0.0)
    }
}
