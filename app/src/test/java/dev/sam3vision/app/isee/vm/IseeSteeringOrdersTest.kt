package dev.sam3vision.app.isee.vm

import dev.sam3vision.app.isee.CrossingLateralWatch
import dev.sam3vision.app.isee.LiveProbe
import dev.sam3vision.app.isee.probeHarness
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @Test
    fun `l activation vocale annonce le passage en vue avec sa distance`() = runTest {
        val (probe, _) = probeHarness(this)
        val dites = mutableListOf<String>()
        val settings = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr)
        val orders = IseeSteeringOrders(
            probe, { settings }, { dites += it }, {},
            crossingAhead = { CrossingAhead(0.5f, 5f) },
        )

        orders.runCrossing("crossing mode on")

        assertTrue(probe.state.value.crossingActive)
        assertEquals(
            listOf("Mode traversée activé.", "Passage piéton devant, à 5 mètres."),
            dites,
        )
    }

    @Test
    fun `l activation vocale sans passage en vue ne dit que le mode`() = runTest {
        val (probe, _) = probeHarness(this)
        val dites = mutableListOf<String>()
        val orders = IseeSteeringOrders(
            probe, { Settings.Default }, { dites += it }, {},
            crossingAhead = { null },
        )

        orders.runCrossing("traverser")

        assertTrue(probe.state.value.crossingActive)
        assertEquals(listOf("Crossing mode on."), dites)
    }

    @Test
    fun `la coupure vocale n annonce pas le passage`() = runTest {
        val (probe, _) = probeHarness(this)
        val dites = mutableListOf<String>()
        val orders = IseeSteeringOrders(
            probe, { Settings.Default }, { dites += it }, {},
            crossingAhead = { CrossingAhead(0.5f, 5f) },
        )
        orders.toggleCrossing()
        dites.clear()

        orders.runCrossing("crossing mode off")

        assertFalse(probe.state.value.crossingActive)
        assertEquals(listOf("Crossing mode off."), dites)
    }

    @Test
    fun `un passage vu il y a longtemps ne s annonce pas`() = runTest {
        val box = listOf(156f, 700f, 356f, 1100f)
        CrossingLateralWatch.update(
            listOf(SegObject(label = "crosswalk", score = 0.8f, box = box, maskPngB64 = "")),
            512, 1138, emptyList(), null, LensSpec.Default,
            System.currentTimeMillis() - 60_000L, SpeechLanguage.Fr,
        )
        try {
            val (probe, _) = probeHarness(this)
            val dites = mutableListOf<String>()
            val settings = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr)
            val orders = IseeSteeringOrders(probe, { settings }, { dites += it }, {})

            orders.runCrossing("crossing mode on")

            assertEquals(listOf("Mode traversée activé."), dites)
        } finally {
            CrossingLateralWatch.reset()
        }
    }

    private fun orders(probe: LiveProbe) =
        IseeSteeringOrders(probe, { Settings.Default }, {}, {})
}
