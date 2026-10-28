package dev.sam3vision.app.isee

import dev.sam3vision.app.depth.DepthMeter
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.LiveFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test

/** La profondeur embarquée tourne à chaque frame capturée, même sans serveur SAM. */
class LiveProbeDepthOfferTest {

    private class FakeDepthMeter : DepthMeter {
        var offers = 0
        override fun offer(jpegB64: String) {
            offers++
        }
        override fun readingsFor(frame: LiveFrame, pinhole: List<DistanceReading>) = pinhole
        override fun reset() {}
    }

    private fun probe(meter: FakeDepthMeter) = LiveProbe(
        scope = CoroutineScope(Dispatchers.Unconfined),
        clock = { 0L },
        background = Dispatchers.Unconfined,
        depthMeter = meter,
    )

    @Test
    fun `offer appele sans session SAM`() {
        val meter = FakeDepthMeter()
        probe(meter).onFrame("aGk=")
        assertEquals(1, meter.offers)
    }

    @Test
    fun `frame vide ignoree`() {
        val meter = FakeDepthMeter()
        probe(meter).onFrame("")
        assertEquals(0, meter.offers)
    }
}
