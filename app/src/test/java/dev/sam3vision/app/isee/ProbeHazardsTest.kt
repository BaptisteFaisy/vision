package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.curb.SidewalkPosition
import dev.sam3vision.core.curb.SidewalkWatcher
import dev.sam3vision.core.hazard.HazardSignal
import dev.sam3vision.core.hazard.HazardWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reset commun des guetteurs de la sonde : bordure + escaliers + mobilier +
 * trottoir oublient leur annonce à chaque début de séance (guidage SAM seul,
 * plus de TwinLite à faire taire).
 */
class ProbeHazardsTest {

    private fun dangers() = ProbeHazards(CurbWatcher(), StairWatcher(), HazardWatcher(), SidewalkWatcher())

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
}
