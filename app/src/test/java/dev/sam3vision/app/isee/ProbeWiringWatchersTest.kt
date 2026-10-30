package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbCue
import dev.sam3vision.core.curb.CurbTier
import dev.sam3vision.core.distance.DistanceReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parole des guetteurs : la bordure (imminente comme pré-alerte) passe même
 * en Traversée pour l'imminente et même après un stop — une alerte de
 * sécurité ne se tait jamais pour une autre phrase ; le bateau reste doux
 * et coupé en Traversée — et les détections sont reconstruites des lectures.
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
    fun `detections reconstruites des lectures, boites illisibles filtrees`() {
        val readings = listOf(
            DistanceReading(1, "car", 5f, 5f, listOf(10f, 20f, 110f, 80f)),
            DistanceReading(2, "bicycle", 2f, 2f, listOf(1f, 2f)),
        )

        val detections = detectionsOf(readings, width = 640, height = 480)

        assertEquals(1, detections.size)
        assertEquals("car", detections.single().label)
        assertEquals(1f, detections.single().score, 0f)
        assertEquals(listOf(10f, 20f, 110f, 80f), detections.single().box)
        assertEquals(640, detections.single().width)
        assertEquals(480, detections.single().height)
    }
}
