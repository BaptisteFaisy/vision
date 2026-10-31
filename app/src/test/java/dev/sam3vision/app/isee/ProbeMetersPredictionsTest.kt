package dev.sam3vision.app.isee

import dev.sam3vision.core.depth.DepthBackend
import dev.sam3vision.core.distance.DistanceMeter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ticker de prédictions de la sonde : temps réel, jamais virtuel. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbeMetersPredictionsTest {

    @Test
    fun `temps virtuel ne declenche pas le ticker`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(dispatcher)
        var calls = 0
        val probe = ProbeMeters(
            scope = scope,
            background = dispatcher,
            appContext = null,
            depthMeter = null,
            depthEngine = { DepthBackend.DA2 },
            onPredictions = { calls++ },
            onDangerAlert = {},
        )
        probe.noteFrame(512, 384)
        probe.startPredictions(DistanceMeter())
        advanceTimeBy(10_000L)
        runCurrent()
        // Temps réel : 10 s virtuelles ne produisent aucun tick (et surtout pas
        // de bouclage d'`advanceUntilIdle` — non-régression du câblage).
        assertEquals(0, calls)
        probe.stopPredictions()
    }
}
