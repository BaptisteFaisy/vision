package dev.sam3vision.app.isee

import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Distances iSee dehors : voitures et bordures seules, tout le reste masqué (ordre user 03/11). */
class ProbeDistanceFilterTest {

    @Test
    fun `dehors, suivis portes sur voiture et bordure seules`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.configureFrom(Settings.Default.copy(outdoorMode = true))
        advanceUntilIdle()
        val followed = probe.state.value.followed.map { it.lowercase() }
        assertTrue(followed.contains("car"))
        assertTrue(followed.contains("curb"))
        assertFalse(followed.contains("person"))
        assertFalse(followed.contains("sidewalk"))
    }

    @Test
    fun `dedans, pas de filtre voiture bordure`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.configureFrom(Settings.Default.copy(outdoorMode = false))
        advanceUntilIdle()
        assertEquals(emptyList<String>(), probe.state.value.followed)
    }

    @Test
    fun `dehors, find my x ajoute au filtre voiture bordure`() = runTest {
        val (probe, _) = probeHarness(this)
        probe.configureFrom(Settings.Default.copy(outdoorMode = true))
        probe.ensureConcept(Settings.Default.copy(outdoorMode = true), "wallet")
        advanceUntilIdle()
        val followed = probe.state.value.followed.map { it.lowercase() }
        assertTrue(followed.contains("wallet"))
        assertTrue(followed.contains("car"))
        assertTrue(followed.contains("curb"))
    }
}
