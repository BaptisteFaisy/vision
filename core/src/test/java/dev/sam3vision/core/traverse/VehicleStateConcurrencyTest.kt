package dev.sam3vision.core.traverse

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Concurrence du veilleur véhicules : `LiveProbe.dispatch` lance un
 * `runFrame` par frame sans garde anti-recouvrement, et l'instance de
 * `CrossingVehicleVoice` est partagée — deux `update` entrelacés ne
 * doivent pas planter (même classe de race que `PersonTracker.sweep`,
 * crash constaté sur émulateur le 01/11).
 */
class VehicleStateConcurrencyTest {

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float, cx: Float = 320f): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(cx - widthPx / 2f, 240f - heightPx / 2f, cx + widthPx / 2f, 240f + heightPx / 2f)
    }

    @Test
    fun `des update concurrents ne plantent pas le veilleur`() {
        val watcher = VehicleStateWatcher()
        val errors = ConcurrentLinkedQueue<Throwable>()
        val start = CountDownLatch(1)
        val threads = List(8) { t ->
            Thread {
                start.await()
                try {
                    repeat(300) { i ->
                        val width = 100f + (i % 20)
                        val b = box(width, 300f + t * 5f)
                        watcher.update(
                            listOf(det("car", b)),
                            listOf(reading("car", 5f, b)),
                            atMs = (t * 300L + i) * 40L,
                        )
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
