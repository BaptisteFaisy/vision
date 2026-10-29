package dev.sam3vision.app.isee

import androidx.compose.ui.unit.dp
import dev.sam3vision.core.nav.NavFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bande carte du mode navigation : sa hauteur sur trois tailles d'écran et le
 * cadre du schéma de repli.
 */
class NavigationMapBandTest {

    private val fix = NavFix(lat = 48.8606, lon = 2.3376, accM = 8f, atMs = 0L)

    @Test
    fun `un telephone courant garde 26 pour cent de la hauteur`() {
        // 800 dp de haut → 208 dp de bande, dans les bornes.
        assertEquals(208.dp, navigationMapBandHeight(800.dp))
    }

    @Test
    fun `un petit ecran est borne par le plancher`() {
        assertEquals(150.dp, navigationMapBandHeight(400.dp))
    }

    @Test
    fun `un grand ecran est borne par le plafond`() {
        assertEquals(260.dp, navigationMapBandHeight(1600.dp))
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
