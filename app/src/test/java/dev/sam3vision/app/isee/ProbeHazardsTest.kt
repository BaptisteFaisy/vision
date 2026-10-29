package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reset commun des guetteurs de la sonde : trottoir + escaliers oublient
 * leur annonce à chaque début de séance (guidage SAM seul, plus de
 * TwinLite à faire taire).
 */
class ProbeHazardsTest {

    private fun dangers() = ProbeHazards(CurbWatcher(), StairWatcher())

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
}
