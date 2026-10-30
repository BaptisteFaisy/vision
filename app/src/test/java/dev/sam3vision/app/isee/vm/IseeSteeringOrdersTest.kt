package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.LiveProbe
import dev.sam3vision.app.isee.probeHarness
import dev.sam3vision.core.settings.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Propriétaire de l'armement Traversée : seul le mode armé par la vision
 * retombe tout seul quand le passage disparaît — le manuel et le GPS
 * restent jusqu'à leur propre coupe.
 */
class IseeSteeringOrdersTest {

    @Test
    fun `l armement vision marque le mode comme auto`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.setAutoCrossing(true, vision = true)

        assertTrue(probe.state.value.crossingActive)
        assertTrue(orders.visionArmed)
    }

    @Test
    fun `l armement GPS ne marque pas le mode comme auto`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)

        orders.setAutoCrossing(true)

        assertTrue(probe.state.value.crossingActive)
        assertFalse(orders.visionArmed)
    }

    @Test
    fun `la bascule manuelle reprend la main`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)
        orders.setAutoCrossing(true, vision = true)

        orders.toggleCrossing()

        assertFalse(probe.state.value.crossingActive)
        assertFalse(orders.visionArmed)
    }

    @Test
    fun `l ordre vocal reprend la main`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)
        orders.setAutoCrossing(true, vision = true)

        orders.runCrossing("stop traverser")

        assertFalse(probe.state.value.crossingActive)
        assertFalse(orders.visionArmed)
    }

    @Test
    fun `la coupe auto efface la marque`() = runTest {
        val (probe, _) = probeHarness(this)
        val orders = orders(probe)
        orders.setAutoCrossing(true, vision = true)

        orders.setAutoCrossing(false, vision = true)

        assertFalse(probe.state.value.crossingActive)
        assertFalse(orders.visionArmed)
    }

    private fun orders(probe: LiveProbe) =
        IseeSteeringOrders(probe, { Settings.Default }, {}, {})
}
