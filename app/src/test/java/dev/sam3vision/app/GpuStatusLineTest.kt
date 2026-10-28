package dev.sam3vision.app

import dev.sam3vision.core.net.GpuPresence
import dev.sam3vision.core.net.GpuStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** La pastille GPU des Réglages (page en anglais) : une ligne par présence observée. */
class GpuStatusLineTest {

    @Test
    fun `quatre presences — quatre lignes`() {
        assertEquals(
            "GPU status: connected ✓",
            gpuStatusLine(GpuStatus("https://sam.test", GpuPresence.Up)),
        )
        assertEquals(
            "GPU status: checking…",
            gpuStatusLine(GpuStatus("https://sam.test", GpuPresence.Checking)),
        )
        assertEquals(
            "GPU status: unreachable.",
            gpuStatusLine(GpuStatus("https://sam.test", GpuPresence.Down("coupé"))),
        )
        assertEquals(
            "GPU status: unknown — run “Test SAM”.",
            gpuStatusLine(GpuStatus()),
        )
    }
}
