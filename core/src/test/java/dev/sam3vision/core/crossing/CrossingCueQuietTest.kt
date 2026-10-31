package dev.sam3vision.core.crossing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Silence partagé des voix douces du mode Traversée : le feu parle seul sur
 * sa frame, un ordre nouveau fait taire 2,5 s, une répétition ne réarme pas.
 */
class CrossingCueQuietTest {

    private fun red() = CrossingCue("Don't cross yet", false, CrossingState.Red)

    private fun green() = CrossingCue("You can cross", false, CrossingState.Green)

    @Test
    fun `la frame de l ordre est muette`() {
        val quiet = CrossingCueQuiet()

        assertTrue(quiet.hush(red(), 100L))
    }

    @Test
    fun `un ordre nouveau fait taire 2 5 s puis rend la parole`() {
        val quiet = CrossingCueQuiet()

        assertTrue(quiet.hush(red(), 100L))
        assertTrue("ordre récent : silence", quiet.hush(null, 200L))
        assertFalse("2,5 s écoulées : parole", quiet.hush(null, 2600L))
    }

    @Test
    fun `la repetition du rouge ne rearme pas le silence`() {
        val quiet = CrossingCueQuiet()

        assertTrue(quiet.hush(red(), 100L))
        assertTrue(quiet.hush(red(), 2000L))
        assertFalse("répétition ignorée : parole à 2,6 s", quiet.hush(null, 2700L))
    }

    @Test
    fun `un ordre different rearme le silence`() {
        val quiet = CrossingCueQuiet()

        assertTrue(quiet.hush(red(), 100L))
        assertFalse(quiet.hush(null, 2700L))
        assertTrue(quiet.hush(green(), 2800L))
        assertTrue("nouvel ordre : silence", quiet.hush(null, 2900L))
    }

    @Test
    fun `sans ordre jamais de silence`() {
        val quiet = CrossingCueQuiet()

        assertFalse(quiet.hush(null, 0L))
        assertFalse(quiet.hush(null, 10_000L))
    }

    @Test
    fun `reset oublie l ordre`() {
        val quiet = CrossingCueQuiet()

        assertTrue(quiet.hush(red(), 100L))
        quiet.reset()

        assertFalse(quiet.hush(null, 200L))
    }
}
