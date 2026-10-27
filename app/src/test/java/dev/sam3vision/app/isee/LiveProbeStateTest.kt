package dev.sam3vision.app.isee

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** État caméra : l'effacement garde l'endpoint, le GPU et les concepts suivis. */
class LiveProbeStateTest {

    @Test
    fun `cleared garde endpoint gpu et concepts`() {
        val state = LiveProbeState(
            endpoint = "https://gpu.example",
            gpu = "A100",
            conceptsSent = listOf("personne"),
            objects = 3,
            frames = 10,
            error = "boom",
            spoken = "Personne à gauche.",
        )
        val cleared = state.cleared()
        assertEquals("https://gpu.example", cleared.endpoint)
        assertEquals("A100", cleared.gpu)
        assertEquals(listOf("personne"), cleared.conceptsSent)
        assertEquals(0, cleared.objects)
        assertEquals(0, cleared.frames)
        assertNull(cleared.error)
        assertNull(cleared.spoken)
    }
}
