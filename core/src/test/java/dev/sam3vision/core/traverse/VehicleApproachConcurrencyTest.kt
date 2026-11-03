package dev.sam3vision.core.traverse

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrence du suivi véhicules : `LiveProbe.dispatch` lance un
 * `runFrame` par frame sans garde anti-recouvrement, et l'instance de
 * `VehicleApproach` est partagée — deux `update` entrelacés (ou un
 * `reset` pendant un `update`) ne doivent pas planter (même famille de
 * race que `PersonTracker.sweep` et le veto, crashs constatés sur
 * émulateur le 01/11).
 */
class VehicleApproachConcurrencyTest {

    @Test
    fun `des update concurrents ne plantent pas le suivi`() {
        val approach = VehicleApproach()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = List(8) { t ->
            Thread {
                start.await()
                try {
                    repeat(300) { i ->
                        val b = vehicleBox(100f + (i % 20), 300f + t * 5f)
                        approach.update(listOf(vehicleDet("car", b)), (t * 300L + i) * 40L)
                        if (i % 100 == 0) approach.reset()
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }.also { it.start() }
        }
        start.countDown()
        threads.forEach { it.join(60_000) }
        assertTrue("plantage concurrent : ${errors.peek()}", errors.isEmpty())
    }
}
