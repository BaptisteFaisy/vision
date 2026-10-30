package dev.sam3vision.core.hazard

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Guetteur grillage : deux frames confirment, dix secondes de silence, la phrase dit l'état. */
class FenceOpeningWatcherTest {

    private fun closed(meters: Float = 2f) = FenceOpeningSignal(
        detected = true,
        state = FenceOpeningState.Closed,
        position = FenceOpeningPosition.Ahead,
        meters = meters,
        centerX = 0.5f,
    )

    private fun open(
        position: FenceOpeningPosition = FenceOpeningPosition.Left,
        meters: Float = 2f,
        centerX: Float? = 0.16f,
        isGate: Boolean = false,
        middle: Boolean = false,
    ) = FenceOpeningSignal(
        detected = true,
        state = FenceOpeningState.Open,
        position = position,
        meters = meters,
        centerX = centerX,
        isGate = isGate,
        middle = middle,
    )

    @Test
    fun `deux positifs parlent, un isole non`() {
        val watcher = FenceOpeningWatcher()

        assertNull(watcher.onSignal(closed(), 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(closed(), 2_000L, SpeechLanguage.Fr)

        assertEquals("Grillage fermé devant vous.", cue!!.phrase)
        assertEquals(2f, cue.meters!!, 0.001f)
        assertEquals(FenceOpeningState.Closed, cue.state)
        assertEquals(FenceOpeningPosition.Ahead, cue.position)
        assertEquals(2_000L, watcher.lastAlertMs)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = FenceOpeningWatcher()

        watcher.onSignal(closed(), 1_000L)
        watcher.onSignal(FenceOpeningSignal.none(), 2_000L)
        assertNull(watcher.onSignal(closed(), 3_000L))
        assertNotNull(watcher.onSignal(closed(), 4_000L))
    }

    @Test
    fun `cooldown dix secondes sans repetition`() {
        val watcher = FenceOpeningWatcher()
        val positive = closed()

        watcher.onSignal(positive, 1_000L)
        assertNotNull(watcher.onSignal(positive, 2_000L))
        assertNull(watcher.onSignal(positive, 3_000L))
        assertNull(watcher.onSignal(positive, 4_000L))
        assertNull("encore en cooldown", watcher.onSignal(positive, 11_999L))
        assertNotNull("cooldown écoulé", watcher.onSignal(positive, 12_000L))
        assertNull("le compte repart de zéro", watcher.onSignal(positive, 13_000L))
    }

    @Test
    fun `ouverture a gauche FR exacte`() {
        val watcher = FenceOpeningWatcher()

        watcher.onSignal(open(), 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(open(), 2_000L, SpeechLanguage.Fr)

        assertEquals("Ouverture à gauche, à 2 mètres.", cue!!.phrase)
    }

    @Test
    fun `passage au milieu FR exact`() {
        val watcher = FenceOpeningWatcher()
        val signal = open(FenceOpeningPosition.Ahead, 2f, 0.5f, middle = true)

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Passage au milieu.", cue!!.phrase)
    }

    @Test
    fun `portail ouvert a droite FR exact`() {
        val watcher = FenceOpeningWatcher()
        val signal = open(FenceOpeningPosition.Right, 3f, 0.84f, isGate = true)

        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Portail ouvert à droite, à 3 mètres.", cue!!.phrase)
    }

    @Test
    fun `cadran explicite sur demande`() {
        val watcher = FenceOpeningWatcher()

        watcher.onSignal(open(), 1_000L, SpeechLanguage.Fr, centerX = 0.1f)
        val cue = watcher.onSignal(open(), 2_000L, SpeechLanguage.Fr, centerX = 0.1f)

        assertEquals("Ouverture à gauche, à 2 mètres, à 9 heures.", cue!!.phrase)
    }

    @Test
    fun `etat sans position = silence`() {
        val watcher = FenceOpeningWatcher()
        val broken = FenceOpeningSignal(detected = true, state = FenceOpeningState.Open)

        assertNull(watcher.onSignal(broken, 1_000L))
        assertNull(watcher.onSignal(broken, 2_000L))
        assertNull(watcher.lastAlertMs)
    }

    @Test
    fun `anglais`() {
        val watcher = FenceOpeningWatcher()

        watcher.onSignal(closed(), 1_000L, SpeechLanguage.En)
        val cue = watcher.onSignal(closed(), 2_000L, SpeechLanguage.En)

        assertEquals("Fence closed ahead.", cue!!.phrase)
    }

    @Test
    fun `reset re-arme l'annonce`() {
        val watcher = FenceOpeningWatcher()

        watcher.onSignal(closed(), 1_000L)
        assertNotNull(watcher.onSignal(closed(), 2_000L))
        watcher.reset()

        assertNull(watcher.lastAlertMs)
        assertNull(watcher.onSignal(closed(), 3_000L))
        assertNotNull(watcher.onSignal(closed(), 4_000L))
    }
}
