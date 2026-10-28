package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Caméra coupée = carte éteinte : `stop` ferme les deux sessions (normale + froide),
 * jette la réponse en vol et refuse les frames suivantes — et `configure` referme les
 * anciennes sessions au lieu de les orpheliner (un socket orphelin garderait le
 * conteneur Modal allumé, facturé).
 */
class LiveProbeStopTest {

    @Test
    fun `stop ferme les sessions et coupe l_envoi`() = runTest {
        val (probe, fake) = harness(this)
        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(1, probe.state.value.frames)

        probe.stop()

        assertFalse(probe.wantsFrame())
        probe.onFrame("B")
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(2, fake.closed)
        assertEquals(0, probe.state.value.frames)
    }

    @Test
    fun `stop jette la reponse en vol`() = runTest {
        val (probe, fake) = harness(this)
        probe.onFrame("A")
        advanceUntilIdle()
        probe.stop()

        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()

        assertEquals(0, probe.state.value.frames)
    }

    @Test
    fun `configure referme les anciennes sessions`() = runTest {
        val (probe, fake) = harness(this)

        probe.configure(ENDPOINT)

        assertEquals(2, fake.closed)
        probe.onFrame("A")
        advanceUntilIdle()
        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()
        assertEquals(1, fake.sent)
        assertEquals(1, probe.state.value.frames)
    }

    /** Sonde branchée sur le faux client partagé, sans réseau ni GPU. */
    private fun harness(scope: TestScope): Pair<LiveProbe, ProbeTestClient> {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(
            scope = scope,
            clock = { 0L },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, _ -> fake },
        )
        probe.configure(ENDPOINT)
        probe.begin()
        return probe to fake
    }

    private companion object {
        const val ENDPOINT = "https://sam.test"
    }
}
