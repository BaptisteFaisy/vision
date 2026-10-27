package dev.sam3vision.app.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repli tracker → `/live` : une seule fois, et seulement avant la première frame. */
class LiveTrackFallbackTest {

    @Test
    fun `repli au premier echec tracker pendant starting`() {
        assertTrue(LiveTrackFallback().shouldFallBack(starting = true, onTracker = true))
    }

    @Test
    fun `pas de repli hors starting ni hors tracker`() {
        val fallback = LiveTrackFallback()
        assertFalse(fallback.shouldFallBack(starting = false, onTracker = true))
        assertFalse(fallback.shouldFallBack(starting = true, onTracker = false))
        // Les refus ne consomment pas le repli : il reste disponible.
        assertTrue(fallback.shouldFallBack(starting = true, onTracker = true))
    }

    @Test
    fun `repli consomme une seule fois puis reset re-arme`() {
        val fallback = LiveTrackFallback()
        assertTrue(fallback.shouldFallBack(starting = true, onTracker = true))
        assertFalse(fallback.shouldFallBack(starting = true, onTracker = true))
        fallback.reset()
        assertTrue(fallback.shouldFallBack(starting = true, onTracker = true))
    }
}
