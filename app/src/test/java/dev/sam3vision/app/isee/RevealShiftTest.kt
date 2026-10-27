package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le complément de défilement qui rend la bulle sélectionnée entièrement visible. */
class RevealShiftTest {

    @Test
    fun `une bulle deja entierement visible ne fait pas defiler`() {
        assertEquals(0f, revealShift(top = 120, bottom = 220, headerPx = 100, sea = 900))
    }

    @Test
    fun `une bulle cachee sous le bandeau fait descendre le fil`() {
        assertEquals(-40f, revealShift(top = 60, bottom = 160, headerPx = 100, sea = 900))
    }

    @Test
    fun `une bulle coupee par la barre du bas fait remonter le fil`() {
        assertEquals(30f, revealShift(top = 850, bottom = 930, headerPx = 100, sea = 900))
    }

    @Test
    fun `le defilement est nul quand la bulle remplit exactement la bande`() {
        assertEquals(0f, revealShift(top = 100, bottom = 900, headerPx = 100, sea = 900))
        assertTrue(revealShift(top = 101, bottom = 899, headerPx = 100, sea = 900) == 0f)
    }
}
