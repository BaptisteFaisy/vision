package dev.sam3vision.app.detect

import dev.sam3vision.core.stairs.StairSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StairFrameTest {

    @Test
    fun `signal positif = bande ROI plein-largeur`() {
        val frame = stairFrameFor(
            StairSignal(detected = true, confidence = 0.66f, lineCount = 5, roiTop = 144, roiBottom = 240),
            width = 320,
        )!!

        assertEquals(0f, frame.left, 0f)
        assertEquals(144f, frame.top, 0f)
        assertEquals(320f, frame.right, 0f)
        assertEquals(240f, frame.bottom, 0f)
        assertEquals("ESCALIER 0.66", frame.label)
    }

    @Test
    fun `signal negatif ou largeur nulle = rien a peindre`() {
        assertNull(stairFrameFor(StairSignal.none(), 320))
        val positive = StairSignal(detected = true, confidence = 1f, lineCount = 6, roiTop = 10, roiBottom = 60)
        assertNull(stairFrameFor(positive, 0))
        assertNull(stairFrameFor(positive, -4))
    }
}
