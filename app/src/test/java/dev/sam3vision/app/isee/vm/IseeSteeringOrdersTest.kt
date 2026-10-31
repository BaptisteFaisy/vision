package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.LiveProbe
import dev.sam3vision.app.isee.probeHarness
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bascule manuelle du mode Traversée : puce et ordre vocal (« traverser »,
 * « crossing mode on/off ») — plus aucun armement automatique.
 */
class IseeSteeringOrdersTest {

    @Test
    fun `la bascule manuelle active puis coupe`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.toggleCrossing()

        assertTrue(probe.state.value.crossingActive)

        orders.toggleCrossing()

        assertFalse(probe.state.value.crossingActive)
    }

    @Test
    fun `l ordre vocal crossing mode on active`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.runCrossing("crossing mode on")

        assertTrue(probe.state.value.crossingActive)
    }

    @Test
    fun `l ordre vocal crossing mode off coupe`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)
        orders.toggleCrossing()

        orders.runCrossing("crossing mode off")

        assertFalse(probe.state.value.crossingActive)
    }

    @Test
    fun `l ordre vocal traverser active stop traverser coupe`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.runCrossing("je veux traverser")
        assertTrue(probe.state.value.crossingActive)

        orders.runCrossing("stop traverser")
        assertFalse(probe.state.value.crossingActive)
    }

    @Test
    fun `un texte ordinaire ne touche pas au mode`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.runCrossing("décris-moi la pièce")

        assertFalse(probe.state.value.crossingActive)
    }

    private fun orders(probe: LiveProbe) =
        IseeSteeringOrders(probe, { Settings.Default }, {}, {})
}
