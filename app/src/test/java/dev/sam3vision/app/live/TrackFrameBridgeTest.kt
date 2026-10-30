package dev.sam3vision.app.live

import dev.sam3vision.core.model.TrackFrame
import dev.sam3vision.core.model.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackFrameBridgeTest {

    @Test
    fun `le pont propage l encodage et l echelle vers le rendu`() {
        val frame = TrackFrame(
            width = 512,
            height = 512,
            objects = listOf(
                TrackedObject(3, "person", 0.9f, listOf(1f, 2f, 30f, 40f), "AQID", "rle", 0.5f),
                TrackedObject(
                    4, "dog", 0.5f, listOf(5f, 6f, 7f, 8f), "UV==", "poly", 0.25f,
                    listOf(listOf(0, 0, 4, 0, 4, 4, 0, 4)),
                ),
            ),
        )

        val live = frame.asLiveFrame()

        assertEquals(512, live.width)
        assertEquals(512, live.height)
        assertEquals("rle", live.objects[0].maskEncoding)
        assertEquals(0.5f, live.objects[0].maskScale, 0.001f)
        assertEquals("AQID", live.objects[0].maskPngB64)
        assertEquals("poly", live.objects[1].maskEncoding)
        assertEquals(listOf(listOf(0, 0, 4, 0, 4, 4, 0, 4)), live.objects[1].maskPoly)
    }

    @Test
    fun `le pont garde le png historique sans encodage`() {
        val live = TrackFrame(0, 0, listOf(TrackedObject(-1, "x", 0f, emptyList(), ""))).asLiveFrame()

        assertEquals("png", live.objects.single().maskEncoding)
        assertEquals(1f, live.objects.single().maskScale, 0.001f)
        assertEquals(emptyList<List<Int>>(), live.objects.single().maskPoly)
    }
}
