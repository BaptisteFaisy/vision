package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class StairWatcherTest {

    private fun positive(lineCount: Int = 5) =
        StairSignal(detected = true, confidence = 0.66f, lineCount = lineCount, roiTop = 24, roiBottom = 60)

    private val negative = StairSignal.none()

    @Test
    fun `trois positifs consecutifs = annonce`() {
        val watcher = StairWatcher()

        assertNull(watcher.onSignal(positive(), 1000L))
        assertNull(watcher.onSignal(positive(), 2000L))
        val cue = watcher.onSignal(positive(), 3000L)

        assertNotNull(cue)
        assertEquals("Escalier devant.", cue!!.phrase)
        assertEquals(5, cue.lineCount)
    }

    @Test
    fun `l'heure situe l'annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.Fr, StairDirection.Up, 0.3f)

        assertEquals("Escalier montant, à 11 heures.", cue!!.phrase)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        watcher.onSignal(negative, 3000L)
        assertNull(watcher.onSignal(positive(), 4000L))
        assertNull(watcher.onSignal(positive(), 5000L))
        assertNotNull(watcher.onSignal(positive(), 6000L))
    }

    @Test
    fun `cooldown tait la repetition puis relache`() {
        val watcher = StairWatcher(cooldownMs = 10_000L)

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        assertNotNull(watcher.onSignal(positive(), 3000L))
        watcher.onSignal(positive(), 4000L)
        watcher.onSignal(positive(), 5000L)
        // Compte saturé (3) mais cooldown : tu, et le compte reste saturé —
        // l'escalier toujours là se ré-annonce dès la fin du silence.
        assertNull(watcher.onSignal(positive(), 6000L))
        assertNotNull(watcher.onSignal(positive(), 13_001L))
    }

    @Test
    fun `phrase anglaise sur demande`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.En)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.En)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.En)

        assertEquals("Stairs ahead.", cue!!.phrase)
    }

    @Test
    fun `reset oublie compte et annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L)
        watcher.onSignal(positive(), 2000L)
        watcher.reset()
        assertNull(watcher.onSignal(positive(), 3000L))
        assertNull(watcher.onSignal(positive(), 4000L))
        assertNotNull(watcher.onSignal(positive(), 5000L))
    }

    @Test
    fun `phrases FR et EN`() {
        assertEquals("Escalier devant.", StairText.phrase(SpeechLanguage.Fr))
        assertEquals("Stairs ahead.", StairText.phrase(SpeechLanguage.En))
        assertEquals("Escalier montant.", StairText.phrase(SpeechLanguage.Fr, StairDirection.Up))
        assertEquals("Escalier descendant.", StairText.phrase(SpeechLanguage.Fr, StairDirection.Down))
        assertEquals("Stairs up.", StairText.phrase(SpeechLanguage.En, StairDirection.Up))
        assertEquals("Stairs down.", StairText.phrase(SpeechLanguage.En, StairDirection.Down))
    }

    @Test
    fun `le sens traverse le guetteur jusqu'a l'annonce`() {
        val watcher = StairWatcher()

        watcher.onSignal(positive(), 1000L, SpeechLanguage.Fr, StairDirection.Up)
        watcher.onSignal(positive(), 2000L, SpeechLanguage.Fr, StairDirection.Up)
        val cue = watcher.onSignal(positive(), 3000L, SpeechLanguage.Fr, StairDirection.Up)

        assertEquals("Escalier montant.", cue!!.phrase)
        assertEquals(StairDirection.Up, cue.direction)
    }
}
