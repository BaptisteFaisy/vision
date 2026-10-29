package dev.sam3vision.app

import dev.sam3vision.core.picto.PictoHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PictoOverlayTest {

    private val hit = PictoHit(pictoId = "p1", label = "medics", x = 0.25f, y = 0.5f, w = 0.5f, h = 0.25f, score = 0.9f)

    @Test
    fun `les boites suivent la transformation crop de l apercu`() {
        val rects = pictoRects(listOf(hit), 200, 100, cropTransform(200, 100, 400f, 400f))
        assertEquals(1, rects.size)
        val rect = rects.single()
        assertEquals("medics", rect.label)
        // Échelle 4 (400/100), image 800x400 centrée : x -200, y 0.
        assertEquals(0f, rect.left)
        assertEquals(200f, rect.top)
        assertEquals(400f, rect.right)
        assertEquals(300f, rect.bottom)
    }

    @Test
    fun `aucune detection, aucun rectangle`() {
        assertTrue(pictoRects(emptyList(), 200, 100, cropTransform(200, 100, 400f, 400f)).isEmpty())
    }
}
