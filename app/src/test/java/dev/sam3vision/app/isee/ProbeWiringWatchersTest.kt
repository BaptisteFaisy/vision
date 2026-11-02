package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parole des guetteurs : la bordure (imminente comme pré-alerte) passe même
 * en Traversée pour l'imminente et même après un stop — une alerte de
 * sécurité ne se tait jamais pour une autre phrase ; le bateau reste doux
 * et coupé en Traversée.
 */
class ProbeWiringWatchersTest {

    private fun cue(tier: CurbTier) = CurbCue("marche", 0.5f, tier)

    @Test
    fun `marche imminente passe meme en traversee`() {
        assertTrue(shouldSpeakCurb(cue(CurbTier.NEAR), crossingActive = true))
        assertTrue(shouldSpeakCurb(cue(CurbTier.NEAR), crossingActive = false))
    }

    @Test
    fun `pre-alerte et bateau doux, coupes en traversee`() {
        assertFalse(shouldSpeakCurb(cue(CurbTier.FAR), crossingActive = true))
        assertFalse(shouldSpeakCurb(cue(CurbTier.NONE), crossingActive = true))
        assertTrue(shouldSpeakCurb(cue(CurbTier.FAR), crossingActive = false))
        assertTrue(shouldSpeakCurb(cue(CurbTier.NONE), crossingActive = false))
    }

    @Test
    fun `urgence parlee = stop ou alerte mouvement traversee`() {
        assertTrue(spokenUrgentOf("Stop."))
        assertTrue(spokenUrgentOf("obstacle, stop."))
        assertTrue(spokenUrgentOf("Stop, car moving."))
        assertFalse(spokenUrgentOf("Car moving, 5 meters away."))
        assertFalse(spokenUrgentOf("left."))
    }
}
