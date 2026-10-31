package dev.sam3vision.app.isee

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `configure` réveille la carte en tâche de fond ; sans endpoint, rien ne part. */
class LiveProbeWakeTest {

    @Test
    fun `le health OK bascule sur la session chaude sans attendre la frame`() = runTest {
        val (probe, _) = probeHarness(this)

        assertTrue(probe.state.value.warming)
        advanceUntilIdle()

        assertFalse("carte chaude prouvée : session chaude", probe.state.value.warming)
        assertEquals(10L, probe.state.value.timeoutSec)
    }

    @Test
    fun `configure reveille la carte en tache de fond`() = runTest {
        val (_, fake) = probeHarness(this)

        advanceUntilIdle()

        assertEquals(1, fake.healthCalls)
    }

    @Test
    fun `configure sans endpoint ne reveille rien`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = this,
            clock = { 0L },
            clientFor = { _, _, _ -> fake },
        )
        probe.configure("")

        advanceUntilIdle()

        assertEquals(0, fake.healthCalls)
    }
}
