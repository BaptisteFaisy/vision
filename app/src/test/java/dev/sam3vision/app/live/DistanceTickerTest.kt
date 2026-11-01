package dev.sam3vision.app.live

import dev.sam3vision.app.DistanceLayer
import dev.sam3vision.core.distance.DistanceMeter
import dev.sam3vision.core.distance.DistanceReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ticker des prédictions : cadence contractuelle et couche des pastilles. */
@OptIn(ExperimentalCoroutinesApi::class)
class DistanceTickerTest {

    @Test
    fun `pas du ticker a 10 Hz avec marge, sous les 150 ms exiges`() {
        assertEquals(80L, DistanceTicker.TICK_MS)
        assertTrue(DistanceTicker.TICK_MS <= 150L)
    }

    @Test
    fun `un tick toutes les 80 ms en temps virtuel`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val ticks = mutableListOf<Long>()
        var now = 10_000L
        val ticker = DistanceTicker(TestScope(dispatcher), dispatcher, clockMs = { now }) { ticked ->
            ticks.add(ticked)
        }
        ticker.start()
        advanceTimeBy(79L)
        runCurrent()
        assertTrue(ticks.isEmpty())
        now += 80L
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf(10_080L), ticks)
        now += 80L
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(2, ticks.size)
        ticker.stop()
        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2, ticks.size)
    }

    @Test
    fun `double start ne duplique pas les ticks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var count = 0
        val ticker = DistanceTicker(TestScope(dispatcher), dispatcher) { count++ }
        ticker.start()
        ticker.start()
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(1, count)
        ticker.stop()
    }

    @Test
    fun `couche predite nulle sans lecture ni dimensions`() {
        val readings = listOf(DistanceReading(1, "personne", 3f, 3f, listOf(0f, 0f, 10f, 10f)))
        assertNull(predictedLayer(512, 384, emptyList(), 67f))
        assertNull(predictedLayer(0, 384, readings, 67f))
        assertNull(predictedLayer(512, 0, readings, 67f))
    }

    @Test
    fun `couche predite porte lectures et champ`() {
        val readings = listOf(DistanceReading(1, "personne", 3f, 3f, listOf(0f, 0f, 10f, 10f)))
        val withFov = predictedLayer(512, 384, readings, 67f)!!
        assertEquals(512, withFov.width)
        assertEquals(384, withFov.height)
        assertEquals(readings, withFov.readings)
        assertEquals(67f, withFov.fovDeg, 0f)
        val probe = predictedLayer(512, 384, readings, null)!!
        assertEquals(0f, probe.fovDeg, 0f)
        assertEquals(readings, probe.readings)
    }

    @Test
    fun `ticker sur meter reel republie les maintenus`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val meter = DistanceMeter()
        val layers = mutableListOf<DistanceLayer?>()
        val ticker = DistanceTicker(TestScope(dispatcher), dispatcher) { now ->
            layers.add(predictedLayer(512, 384, meter.predictions(now), 67f))
        }
        ticker.start()
        advanceTimeBy(80L)
        runCurrent()
        assertEquals(1, layers.size)
        assertNull(layers.first())
        ticker.stop()
    }
}
