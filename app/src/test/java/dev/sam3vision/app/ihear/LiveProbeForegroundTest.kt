package dev.sam3vision.app.ihear

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La carte GPU ne travaille que quand l'app est au premier plan : en arrière-plan, la
 * sonde refuse les frames (`wantsFrame`) et jette celles qui arrivent quand même
 * (`onFrame`) — sinon l'enregistrement rallumerait la carte après son extinction.
 */
class LiveProbeForegroundTest {

    @Test
    fun `en arriere-plan aucune frame ne part`() = runTest {
        val (probe, fake) = harness(this)
        probe.onBackground()

        assertFalse(probe.wantsFrame())
        probe.onFrame("A")
        advanceUntilIdle()

        assertEquals(0, fake.sent)
    }

    @Test
    fun `au retour au premier plan les frames repartent`() = runTest {
        val (probe, fake) = harness(this)
        probe.onBackground()
        probe.onForeground()

        assertTrue(probe.wantsFrame())
        probe.onFrame("A")
        advanceUntilIdle()
        assertEquals(1, fake.sent)

        fake.respond("A", LiveFrame(width = 512, height = 512, elapsedMs = 111, objects = emptyList()))
        advanceUntilIdle()
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
