package dev.sam3vision.app.voicenav

import dev.sam3vision.core.nav.NavFix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Textes et cadre du mode navigation : ce que l'écran écrit quand la
 * position arrive, quand elle cherche, et quand elle est refusée.
 */
class MapModeScreenTest {

    private val fix = NavFix(lat = 48.8606, lon = 2.3376, accM = 8f, atMs = 0L)

    @Test
    fun `sans fix mais avec permission on cherche la position`() {
        assertEquals("Recherche de la position…", positionLine(null, gpsAvailable = true))
    }

    @Test
    fun `sans permission on dit quoi autoriser`() {
        assertEquals(
            "Position indisponible — autorise la localisation pour ce téléphone.",
            positionLine(null, gpsAvailable = false),
        )
    }

    @Test
    fun `avec un fix on donne les coordonnees et la precision`() {
        assertEquals(
            "Position 48,86060, 2,33760 — précision 8 m.",
            positionLine(fix, gpsAvailable = true),
        )
    }

    @Test
    fun `le cadre de vue fait 200 m autour de la position`() {
        val bounds = viewBoundsOf(fix)

        assertTrue(bounds.maxLat > fix.lat && bounds.minLat < fix.lat)
        assertTrue(bounds.maxLon > fix.lon && bounds.minLon < fix.lon)
        // 200 m en latitude ≈ 0,00180°.
        assertEquals(0.001797, bounds.maxLat - fix.lat, 1e-5)
    }

    @Test
    fun `sans position le cadre reste projetable`() {
        val bounds = viewBoundsOf(null)

        assertEquals(bounds.minLat, bounds.maxLat, 0.0)
        assertEquals(bounds.minLon, bounds.maxLon, 0.0)
    }
}
