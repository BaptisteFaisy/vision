package dev.sam3vision.app.isee

import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La carte GPU ne travaille que quand l'app est au premier plan : en arrière-plan, la
 * sonde refuse les frames (`wantsFrame`) et jette celles qui arrivent quand même
 * (`onFrame`) — sinon l'enregistrement rallumerait la carte après son extinction.
 */
class LiveProbeForegroundTest {

    /** Ces tests lancent de vraies frames : les globaux de séance suivent. */
    @After
    fun remettreLaSeanceAZero() = seanceVide()

    @Test
    fun `en arriere-plan aucune frame ne part`() = runTest {
        val (probe, fake) = probeHarness(this)
        probe.onBackground()

        assertFalse(probe.wantsFrame())
        probe.onFrame("A")
        advanceUntilIdle()

        assertEquals(0, fake.sent)
    }

    @Test
    fun `au retour au premier plan les frames repartent`() = runTest {
        val (probe, fake) = probeHarness(this)
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

}
