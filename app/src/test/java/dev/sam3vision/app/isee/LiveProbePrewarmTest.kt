package dev.sam3vision.app.isee

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pré-réveil iSee : le boot GPU part dès que la liaison caméra commence, sans
 * attendre `configure` — mais seulement si le budget froid est dû (carte froide
 * ou réveil périmé). Le chemin réseau local est réchauffé avant le `/health`.
 */
class LiveProbePrewarmTest {

    @Test
    fun `prewarm rechauffe le reseau puis reveille quand le froid est du`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(scope = this, clock = { 0L }, clientFor = { _, _, _ -> fake })
        var warmed: String? = null

        probe.prewarm(PROBE_TEST_ENDPOINT) { warmed = it }
        advanceUntilIdle()

        assertEquals(PROBE_TEST_ENDPOINT, warmed)
        assertEquals(1, fake.healthCalls)
    }

    @Test
    fun `prewarm sans endpoint ne reveille rien`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(scope = this, clock = { 0L }, clientFor = { _, _, _ -> fake })
        var warmed: String? = null

        probe.prewarm("  ") { warmed = it }
        advanceUntilIdle()

        assertNull(warmed)
        assertEquals(0, fake.healthCalls)
    }

    @Test
    fun `prewarm carte chaude ne reveille rien`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(scope = this, clock = { 0L }, clientFor = { _, _, _ -> fake })
        probe.link.markPaid(PROBE_TEST_ENDPOINT)
        var warmed: String? = null

        probe.prewarm(PROBE_TEST_ENDPOINT) { warmed = it }
        advanceUntilIdle()

        assertNull(warmed)
        assertEquals(0, fake.healthCalls)
    }

    @Test
    fun `prewarm reussi paie le budget froid avant configure`() = runTest {
        val fake = ProbeTestClient(mutableListOf())
        val probe = LiveProbe(scope = this, clock = { 0L }, clientFor = { _, _, _ -> fake })

        assertTrue(probe.link.coldDue(PROBE_TEST_ENDPOINT))
        probe.prewarm(PROBE_TEST_ENDPOINT) {}
        advanceUntilIdle()

        assertEquals(1, fake.healthCalls)
        assertFalse(
            "le /health OK paie le budget : configure ne reparlera pas de réveil",
            probe.link.coldDue(PROBE_TEST_ENDPOINT),
        )
    }
}
