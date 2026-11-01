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
        // Liste d'intérieur (10 concepts, clés incluses) : il reste de la place sous le plafond.
        probe.configure(PROBE_TEST_ENDPOINT, prompts = LivePrompts.indoor)
        probe.ensureConcept(Settings.Default, "wallet")
        advanceUntilIdle()
        assertTrue(probe.state.value.conceptsSent.contains("wallet"))
    }

    @Test
    fun `suivis explicites portes dans l etat pour les pastilles`() = runTest {
        val (probe, _) = probeHarness(this)
        assertEquals(emptyList<String>(), probe.state.value.followed)
        probe.ensureConcept(Settings.Default, "wallet")
        advanceUntilIdle()
        assertEquals(listOf("wallet"), probe.state.value.followed)
    }

    @Test
    fun `suivis explicites dedans hors liste du monde`() = runTest {
        val (probe, _) = probeHarness(this)
        val indoor = Settings.Default.copy(outdoorMode = false)
        probe.configure(PROBE_TEST_ENDPOINT, prompts = LivePrompts.indoor)
        probe.ensureConcept(indoor, "wallet")
        advanceUntilIdle()
        assertEquals(listOf("wallet"), probe.state.value.followed)
    }

    @Test
    fun `defauts exterieur, find the x ajoute sous le plafond`() = runTest {
        // Spec v4 : le défaut fait 16 concepts — « Find the X » dehors
        // **ajoute** sans évincer (plafond 22), puis reconfigure.
        val (probe, fake) = probeHarness(this)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "wallet")
        advanceUntilIdle()
        assertTrue(fake.closed > closed)
        val sent = probe.state.value.conceptsSent
        assertEquals(17, sent.size)
        assertTrue(sent.contains("wallet"))
        assertTrue(sent.contains("souvenir shop"))
    }

    @Test
    fun `concept present ne reconfigure pas`() = runTest {
        val (probe, fake) = probeHarness(this)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "Person")
        // « wall » est dans les défauts : déjà suivi, pas de reconfiguration.
        probe.ensureConcept(Settings.Default, "wall")
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertEquals(LivePrompts.normalize(LivePrompts.default), probe.state.value.conceptsSent)
    }

    @Test
    fun `traversee activee, prompts intouches`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.configure(PROBE_TEST_ENDPOINT, crossingActive = true)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "wallet")
        advanceUntilIdle()
        assertEquals(closed, fake.closed)
        assertFalse(probe.state.value.conceptsSent.contains("wallet"))
    }

    @Test
    fun `plafond, find the x remplace le dernier, trop long refuse`() = runTest {
        val (probe, fake) = probeHarness(this)
        val full = (1..22).map { "concept$it" }
        probe.configure(PROBE_TEST_ENDPOINT, prompts = full)
        val closed = fake.closed
        probe.ensureConcept(Settings.Default, "keys")
        advanceUntilIdle()
        assertTrue(fake.closed > closed)
        val expected = full.dropLast(1) + "keys"
        assertEquals(expected, probe.state.value.conceptsSent)
        val closedAfterKeys = fake.closed
        probe.ensureConcept(Settings.Default, "x".repeat(33))
        advanceUntilIdle()
        assertEquals(closedAfterKeys, fake.closed)
        assertEquals(expected, probe.state.value.conceptsSent)
    }
}
