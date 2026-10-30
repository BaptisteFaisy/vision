package dev.sam3vision.app.isee

import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** « Find the X » côté sonde : X rejoint les concepts SAM, une seule fois. */
class ProbeEnsureConceptTest {

    @Test
    fun `concept absent rejoint les masques sam`() = runTest {
        val (probe, _) = probeHarness(this)
        // Liste d'intérieur (7 concepts) : il reste de la place sous le plafond.
        probe.configure(PROBE_TEST_ENDPOINT, prompts = LivePrompts.indoor)
        probe.ensureConcept(Settings.Default, "keys")
        advanceUntilIdle()
        assertTrue(probe.state.value.conceptsSent.contains("keys"))
    }

    @Test
    fun `defauts exterieur au plafond, find the x refuse sans reconfigurer`() = runTest {
        // Liste unique normal/Traversée (30/10) : 16 concepts, au plafond —
        // « Find the X » dehors est refusé comme tout 17e concept, sans coupure.
        val (probe, fake) = probeHarness(this)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "keys")
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertEquals(16, probe.state.value.conceptsSent.size)
        assertFalse(probe.state.value.conceptsSent.contains("keys"))
    }

    @Test
    fun `concept present ne reconfigure pas`() = runTest {
        val (probe, fake) = probeHarness(this)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "Person")
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertEquals(LivePrompts.normalize(LivePrompts.default), probe.state.value.conceptsSent)
    }

    @Test
    fun `traversee activee, prompts intouches`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.configure(PROBE_TEST_ENDPOINT, crossingActive = true)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "keys")
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertFalse(probe.state.value.conceptsSent.contains("keys"))
    }

    @Test
    fun `plafond ou label trop long, pas de reconfiguration`() = runTest {
        val (probe, fake) = probeHarness(this)
        val sixteen = (1..16).map { "concept$it" }
        probe.configure(PROBE_TEST_ENDPOINT, prompts = sixteen)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "keys")
        probe.ensureConcept(Settings.Default, "x".repeat(33))
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertEquals(sixteen, probe.state.value.conceptsSent)
    }
}
