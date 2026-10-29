package dev.sam3vision.core.nav

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Détour subi : recalcul après 8 s continues, une fois par minute au plus. */
class DetourRerouteTest {

    @Test
    fun `detour bref = silence`() {
        val reroute = DetourReroute()

        assertFalse(reroute.onDetour(true, 1_000L))
        assertFalse(reroute.onDetour(true, 8_000L))
    }

    @Test
    fun `detour subi huit secondes = feu`() {
        val reroute = DetourReroute()
        reroute.onDetour(true, 1_000L)

        assertTrue(reroute.onDetour(true, 9_000L))
    }

    @Test
    fun `retour au cap remet le compteur a zero`() {
        val reroute = DetourReroute()
        reroute.onDetour(true, 1_000L)
        reroute.onDetour(false, 5_000L)

        assertFalse(reroute.onDetour(true, 9_000L))
    }

    @Test
    fun `cooldown tait le second feu`() {
        val reroute = DetourReroute()
        reroute.onDetour(true, 1_000L)
        reroute.onDetour(true, 9_000L)

        assertFalse(reroute.onDetour(true, 20_000L))
        assertTrue(reroute.onDetour(true, 69_001L))
    }

    @Test
    fun `reset oublie tout`() {
        val reroute = DetourReroute()
        reroute.onDetour(true, 1_000L)
        reroute.onDetour(true, 9_000L)

        reroute.reset()

        assertFalse(reroute.onDetour(true, 10_000L))
    }
}
