package dev.sam3vision.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Frames jetées du Direct ([LiveFrameDrops]) : la porte `wantsFrame` fermée compte,
 * elle ne perd jamais en silence. Pur, testé en JVM (le log Android ne part qu'au 60e).
 */
class LiveFrameDropsTest {

    @Test
    fun `chaque porte fermee compte une jetee`() {
        val before = LiveFrameDrops.dropped
        LiveFrameDrops.noteDropped()
        LiveFrameDrops.noteDropped()
        assertEquals(before + 2, LiveFrameDrops.dropped)
    }
}
