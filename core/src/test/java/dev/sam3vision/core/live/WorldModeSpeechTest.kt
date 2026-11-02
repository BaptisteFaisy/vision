package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Test

class WorldModeSpeechTest {

    @Test
    fun `le dedans active dit indoor mode`() {
        assertEquals("indoor mode", WorldModeSpeech.announcement(false))
    }

    @Test
    fun `le dehors active dit outdoor mode`() {
        assertEquals("outdoor mode", WorldModeSpeech.announcement(true))
    }
}
