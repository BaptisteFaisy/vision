package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pastille du chip et bandeau du scan : couleurs et géométrie.
 * Tout est pur, donc tout se vérifie sans appareil.
 */
class CrossingOverlayLayoutTest {

    /** Compare un rectangle au millième de pixel : le flottant ne doit pas faire le juge. */
    private fun assertMarker(marker: LightMarker?, left: Float, top: Float, right: Float, bottom: Float) {
        assertNotNull(marker)
        assertEquals(left, marker!!.left, 0.001f)
        assertEquals(top, marker.top, 0.001f)
        assertEquals(right, marker.right, 0.001f)
        assertEquals(bottom, marker.bottom, 0.001f)
    }

    @Test
    fun `mode coupe - pastille grise`() {
        assertEquals(CROSSING_ACCENT_OFF, crossingAccent(false, null))
        assertEquals(CROSSING_ACCENT_OFF, crossingAccent(false, CrossingState.Red))
    }

    @Test
    fun `chaque etat a sa couleur, binaire`() {
        assertEquals(CROSSING_ACCENT_GREEN, crossingAccent(true, CrossingState.Green))
        assertEquals(CROSSING_ACCENT_RED, crossingAccent(true, CrossingState.Red))
        assertEquals(CROSSING_ACCENT_RED, crossingAccent(true, null))
    }

    @Test
    fun `bandeau centre en haut sous la puce`() {
        // Carte 400x400, bandeau 200x30, marge haute 64 : centré (x=100), top=64.
        val r = verdictRect(tagWidth = 200f, tagHeight = 30f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertMarker(r, 100f, 64f, 300f, 94f)
    }

    @Test
    fun `bandeau trop large colle a gauche`() {
        val r = verdictRect(tagWidth = 500f, tagHeight = 30f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertEquals(0f, r.left, 0.001f)
        assertEquals(64f, r.top, 0.001f)
    }

    @Test
    fun `bandeau trop haut colle en haut`() {
        val r = verdictRect(tagWidth = 100f, tagHeight = 500f, boxWidth = 400f, boxHeight = 400f, topMarginPx = 64f)
        assertEquals(150f, r.left, 0.001f)
        assertEquals(0f, r.top, 0.001f)
    }
}
