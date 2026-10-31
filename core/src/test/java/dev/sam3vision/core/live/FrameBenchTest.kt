package dev.sam3vision.core.live

import java.util.function.LongSupplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrat de [FrameBench.measureBest] : plusieurs passages, **un seul** échantillon gardé,
 * et c'est le meilleur.
 *
 * Le banc est un garde-fou de performance sur runner partagé (voir `MotionBenchTest`) :
 * si `measureBest` gardait la moyenne ou le dernier passage, une frame saine échouerait
 * dès qu'un autre job occupe la machine — c'est exactement ce qui est arrivé le 31/10.
 */
class FrameBenchTest {

    @Test
    fun `measureBest garde le meilleur passage et un seul echantillon`() {
        val bench = FrameBench(warmup = 0, iterations = 1)
        var appels = 0
        // 1er passage rapide, les suivants coûtent 5 ms : le minimum doit être le 1er.
        val op = LongSupplier {
            appels++
            if (appels > 1) Thread.sleep(5)
            1L
        }

        val sample = bench.measureBest("op", repetitions = 3, op = op)

        assertEquals(3, appels)
        assertEquals("op", sample.name)
        assertEquals(1, bench.all().size)
        assertTrue("${sample.microsPerOp} µs/op", sample.microsPerOp < 4_000.0)
    }

    @Test
    fun `measureBest accepte un seul passage`() {
        val bench = FrameBench(warmup = 0, iterations = 1)
        val sample = bench.measureBest("seul", repetitions = 1) { 7L }
        assertEquals("seul", sample.name)
        assertEquals(1, bench.all().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `measureBest refuse zero passage`() {
        val bench = FrameBench(warmup = 0, iterations = 1)
        bench.measureBest("vide", repetitions = 0) { 0L }
    }
}
