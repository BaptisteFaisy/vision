package dev.sam3vision.app

import dev.sam3vision.core.live.FrameTimings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sous-flux du poste « pixel » (Q5b) : le dessin d'une frame pose son instantané
 * sans refaire émettre l'état entier — la ligne chrono collecte ce flux seule.
 */
class LiveStagePublishTest {

    @Test
    fun `vide avant la premiere frame dessinee`() {
        assertTrue(PixelStages().stages.value.isEmpty)
    }

    @Test
    fun `publie l instantane dessine`() {
        val pixel = PixelStages()
        val snap = FrameTimings(captureToPixelMs = 123L)
        pixel.publish(snap)
        assertEquals(snap, pixel.stages.value)
    }

    @Test
    fun `efface a l arret`() {
        val pixel = PixelStages()
        pixel.publish(FrameTimings(captureToPixelMs = 123L))
        pixel.clear()
        assertTrue(pixel.stages.value.isEmpty)
    }
}
