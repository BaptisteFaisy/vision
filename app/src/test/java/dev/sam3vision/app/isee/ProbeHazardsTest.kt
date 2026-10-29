package dev.sam3vision.app.isee

import dev.sam3vision.core.curb.CurbSignal
import dev.sam3vision.core.curb.CurbWatcher
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.stairs.StairSignal
import dev.sam3vision.core.stairs.StairWatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité danger sur le guidage du sol : après une alerte trottoir ou
 * escaliers, la voix TwinLite se tait le temps de la fenêtre (la flèche,
 * elle, reste affichée — voir TwinLiteWiringTest).
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
    fun `sans alerte twinlite parle`() {
        assertFalse(dangers().mutesTwinLite(1_000L))
    }

    @Test
    fun `alerte trottoir tait twinlite`() {
        val dangers = dangers()
        alerteTrottoir(dangers, 1_000L)

        assertTrue(dangers.mutesTwinLite(1_000L))
        assertTrue(dangers.mutesTwinLite(1_000L + ProbeHazards.MUTE_MS - 1))
    }

    @Test
    fun `alerte escalier tait twinlite`() {
        val dangers = dangers()
        alerteEscalier(dangers, 2_000L)

        assertTrue(dangers.mutesTwinLite(2_000L))
    }

    @Test
    fun `la priorite expire`() {
        val dangers = dangers()
        alerteTrottoir(dangers, 1_000L)

        assertFalse(dangers.mutesTwinLite(1_000L + ProbeHazards.MUTE_MS))
    }

    @Test
    fun `reset rend la voix`() {
        val dangers = dangers()
        alerteEscalier(dangers, 1_000L)
        dangers.reset()

        assertFalse(dangers.mutesTwinLite(1_000L))
    }
}
