package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkWatcher

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.hazard.FenceOpeningPosition
import dev.sam3vision.core.hazard.FenceOpeningSignal
import dev.sam3vision.core.hazard.FenceOpeningState
import dev.sam3vision.core.hazard.FenceOpeningWatcher
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairCountVoice
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import dev.sam3vision.core.traverse.VehicleStateWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reset commun des guetteurs de la sonde : bordure + escaliers + compteur +
 * mobilier + grillage + véhicules + cycles + trottoir oublient leur annonce à
 * chaque début de séance (guidage SAM seul, plus de TwinLite à faire taire).
 */
class ProbeHazardsTest {

    private fun dangers() = ProbeHazards(
        CurbWatcher(), StairWatcher(), StairCountVoice(), HazardWatcher(), FenceOpeningWatcher(),
        VehicleStateWatcher(), SidewalkWatcher(),
    )

    private fun det(label: String, box: List<Float>): Detection =
        Detection(label, 0.9f, box, width = 640, height = 480)

    private fun reading(label: String, meters: Float, box: List<Float>): DistanceReading =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** 3 frames positives : les deux guetteurs par défaut confirment à 3. */
    private fun alerteTrottoir(dangers: ProbeHazards, aMs: Long) {
        repeat(3) {
            dangers.curb.onSignal(
                CurbSignal(detected = true, meters = 0.5f), aMs, SpeechLanguage.Fr)
        }
    }

    private fun alerteEscalier(dangers: ProbeHazards, aMs: Long) {
        val signal = StairSignal(
            detected = true, confidence = 1f, lineCount = 4, roiTop = 0, roiBottom = 10)
        repeat(3) { dangers.stairs.onSignal(signal, aMs, SpeechLanguage.Fr) }
    }

    @Test
    fun `reset oublie l'alerte trottoir`() {
        val dangers = dangers()
        alerteTrottoir(dangers, 1_000L)
        assertEquals(1_000L, dangers.curb.lastAlertMs)

        dangers.reset()

        assertNull(dangers.curb.lastAlertMs)
    }

    @Test
    fun `reset oublie l'alerte escalier`() {
        val dangers = dangers()
        alerteEscalier(dangers, 2_000L)
        assertEquals(2_000L, dangers.stairs.lastAlertMs)

        dangers.reset()

        assertNull(dangers.stairs.lastAlertMs)
    }

    @Test
    fun `reset oublie l'alerte mobilier`() {
        val dangers = dangers()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier")
        dangers.hazard.onSignal(signal, 3_000L, SpeechLanguage.Fr)
        dangers.hazard.onSignal(signal, 3_000L, SpeechLanguage.Fr)
        assertEquals(3_000L, dangers.hazard.lastAlertMs)

        dangers.reset()

        assertNull(dangers.hazard.lastAlertMs)
    }

    @Test
    fun `reset oublie l'annonce trottoir`() {
        val dangers = dangers()
        repeat(2) { dangers.sidewalk.onPosition(SidewalkPosition.Left, 4_000L, SpeechLanguage.Fr) }
        assertEquals(4_000L, dangers.sidewalk.lastAlertMs)

        dangers.reset()

        assertNull(dangers.sidewalk.lastAlertMs)
    }
    @Test
    fun `reset oublie l'alerte grillage`() {
        val dangers = dangers()
        val signal = FenceOpeningSignal(
            detected = true, state = FenceOpeningState.Closed,
            position = FenceOpeningPosition.Ahead, meters = 2f,
        )
        dangers.fence.onSignal(signal, 5_000L, SpeechLanguage.Fr)
        dangers.fence.onSignal(signal, 5_000L, SpeechLanguage.Fr)
        assertEquals(5_000L, dangers.fence.lastAlertMs)

        dangers.reset()

        assertNull(dangers.fence.lastAlertMs)
    }

    @Test
    fun `reset oublie l'annonce vehicule`() {
        val dangers = dangers()
        val frame = box(100f)
        repeat(3) { index ->
            dangers.vehicle.update(
                listOf(det("car", frame)), listOf(reading("car", 2f, frame)),
                atMs = 6_000L + index * 100L, language = SpeechLanguage.Fr,
            )
        }
        assertEquals(6_200L, dangers.vehicle.lastAlertMs)

        dangers.reset()

        assertNull(dangers.vehicle.lastAlertMs)
    }

    @Test
    fun `reset oublie le compte dit`() {
        val dangers = dangers()
        assertEquals("5 marches.", dangers.count.onTick(true, 5, 1_000L))
        assertNull(dangers.count.onTick(true, 5, 1_500L))

        dangers.reset()

        assertEquals("5 marches.", dangers.count.onTick(true, 5, 3_000L))
    }


}
