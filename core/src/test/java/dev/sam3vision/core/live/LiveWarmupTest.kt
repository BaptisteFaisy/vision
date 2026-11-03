package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chauffe du direct ([LiveWarmup]) : le délai froid ne tombe qu'une fois par séance. */
class LiveWarmupTest {

    @Test
    fun `le premier succes chauffe, les suivants non`() {
        val warmup = LiveWarmup()
        assertFalse(warmup.warmed)
        assertTrue(warmup.onFirstSuccess())
        assertTrue(warmup.warmed)
        assertFalse(warmup.onFirstSuccess())
    }

    @Test
    fun `reset rearme la chauffe`() {
        val warmup = LiveWarmup()
        warmup.onFirstSuccess()
        warmup.reset()
        assertFalse(warmup.warmed)
        assertTrue(warmup.onFirstSuccess())
    }
}
