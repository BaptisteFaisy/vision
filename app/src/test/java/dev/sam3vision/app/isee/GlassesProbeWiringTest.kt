package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions pures du pont lunettes → SAM ([stairFeedDue]) + parité d'encodage
 * avec le téléphone : le pont envoie en [ISEE_FRAME_EDGE]/[ISEE_FRAME_QUALITY],
 * jamais en 480/q65. Le JPEG lui-même (`Bitmap`) ne s'éprouve que sur appareil.
 */
class GlassesProbeWiringTest {

    @Test
    fun `le premier Canny des marches passe toujours`() {
        assertTrue(stairFeedDue(lastMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun `le Canny des marches est throtte a une seconde`() {
        assertEquals(1_000L, STAIR_FEED_MS)
        assertFalse(stairFeedDue(lastMs = 10_000L, nowMs = 10_999L))
        assertTrue(stairFeedDue(lastMs = 10_000L, nowMs = 11_000L))
    }

    @Test
    fun `le pont encode comme le telephone 320 q55`() {
        // Parité SAM : 320/q55 téléphone (le 480/q65 coûtait 2,25× pixels).
        assertEquals(320, ISEE_FRAME_EDGE)
        assertEquals(55, ISEE_FRAME_QUALITY)
    }
}
