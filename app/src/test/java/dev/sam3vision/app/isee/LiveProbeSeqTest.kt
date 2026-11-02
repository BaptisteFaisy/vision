package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.LiveHealth
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Séquence lunettes : `onFrame` la porte jusqu'à la chronologie publiée, en direct
 * comme via la réserve — la ligne `ISEE_LAT` rejoint alors la ligne `GlassesLink`
 * de même `seq` (jointure E2E hors ligne). Sans elle, téléphone seul : `-1`.
 */
class LiveProbeSeqTest {

    /** Horloge monotone pilotée (ns) : même base qu'`imageInfo.timestamp`. */
    private var ns = 0L

    private fun at(ms: Long) {
        ns = ms * 1_000_000L
    }

    /** Frame sans objet (aucun masque à décorer, aucun Android à moquer) et sa durée serveur. */
    private fun frame(elapsedMs: Long) =
        LiveFrame(width = 512, height = 1138, elapsedMs = elapsedMs, objects = emptyList())

    @Test
    fun `seq direct rejoint la chronologie publiee`() = runTest {
        val (probe, fake) = harness(this)
        at(10)
        probe.onFrame("A", captureAtNs = 8_000_000L, glassesSeq = 12L)
        advanceUntilIdle()
        fake.respond("A", frame(111))
        advanceUntilIdle()

        assertEquals(12L, probe.state.value.timings.seq)
    }

    @Test
    fun `seq en reserve rejoint la chronologie au drain`() = runTest {
        val (probe, fake) = harness(this)
        at(10)
        probe.onFrame("A", captureAtNs = 8_000_000L, glassesSeq = 11L)
        advanceUntilIdle()
        at(15)
        probe.onFrame("B", captureAtNs = 14_000_000L, glassesSeq = 12L)
        advanceUntilIdle()
        at(20)
        probe.onFrame("C", captureAtNs = 18_000_000L, glassesSeq = 13L)
        advanceUntilIdle()
        assertEquals("saturé : C attend en réserve", 2, fake.sent)

        at(40)
        fake.respond("A", frame(111))
        advanceUntilIdle()
        at(60)
        fake.respond("B", frame(222))
        advanceUntilIdle()
        at(80)
        fake.respond("C", frame(333))
        advanceUntilIdle()

        assertEquals("C drainée garde son seq, pas celui des vols", 13L, probe.state.value.timings.seq)
    }

    @Test
    fun `sans seq la jointure reste inactive`() = runTest {
        val (probe, fake) = harness(this)
        at(10)
        probe.onFrame("A", captureAtNs = 8_000_000L)
        advanceUntilIdle()
        fake.respond("A", frame(111))
        advanceUntilIdle()

        assertEquals(-1L, probe.state.value.timings.seq)
    }

    /** Sonde branchée sur un faux client, sans réseau ni GPU (miroir du test d'ordonnancement). */
    private fun harness(scope: TestScope): Pair<LiveProbe, ProbeTestClient> {
        val fake = ProbeTestClient(mutableListOf())
        // Réveil négatif aussitôt : cadence seule — un réveil suspendu pendrait `runTest`.
        fake.healthGate = CompletableDeferred(
            Result.success(LiveHealth(ok = false, model = "sam3.1", device = "cuda", gpu = "A100")),
        )
        val probe = LiveProbe(
            scope = scope,
            clock = { ns },
            background = StandardTestDispatcher(scope.testScheduler),
            clientFor = { _, _, marks -> fake.also { it.marks = marks } },
        )
        probe.configure("https://sam.test")
        probe.begin()
        // Scheduler coupé : ce test verrouille la séquence, pas les keyframes.
        probe.adaptive.configure(uploadEnabled = false, keyframesEnabled = false)
        return probe to fake
    }
}
