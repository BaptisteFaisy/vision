package dev.sam3vision.core.curb

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Le trottoir nommé : deux frames confirment, puis on parle au changement —
 * jamais deux fois la même position, jamais l'absent. Miroir des guetteurs
 * bordure/marches/mobilier, sans leur répétition périodique : stable =
 * silence, le guidage confirme déjà qu'il veille.
 */
class SidewalkWatcherTest {

    @Test
    fun `premier trottoir devant parle apres confirmation`() {
        val watcher = SidewalkWatcher()

        assertNull(watcher.onPosition(SidewalkPosition.Ahead, 0L))
        val cue = watcher.onPosition(SidewalkPosition.Ahead, 200L)!!

        assertEquals("Trottoir devant.", cue.phrase)
        assertEquals(SidewalkPosition.Ahead, cue.position)
        assertEquals(200L, watcher.lastAlertMs)
    }

    @Test
    fun `trottoir en face parle apres confirmation`() {
        val watcher = SidewalkWatcher()

        assertNull(watcher.onPosition(SidewalkPosition.Across, 0L))
        val cue = watcher.onPosition(SidewalkPosition.Across, 200L)!!

        assertEquals("Trottoir en face.", cue.phrase)
        assertEquals(SidewalkPosition.Across, cue.position)
        assertEquals(200L, watcher.lastAlertMs)
    }

    @Test
    fun `stable egal silence`() {
        val watcher = SidewalkWatcher()

        repeat(2) { watcher.onPosition(SidewalkPosition.Ahead, it * 200L) }
        repeat(4) {
            assertNull(watcher.onPosition(SidewalkPosition.Ahead, 1_000L + it * 200L))
        }
    }

    @Test
    fun `changement de cote parle`() {
        val watcher = SidewalkWatcher()

        repeat(2) { watcher.onPosition(SidewalkPosition.Ahead, it * 200L) }
        assertNull(watcher.onPosition(SidewalkPosition.Left, 400L))
        val cue = watcher.onPosition(SidewalkPosition.Left, 600L)!!

        assertEquals("Trottoir à gauche.", cue.phrase)
        assertEquals(SidewalkPosition.Left, cue.position)
    }

    @Test
    fun `passage en face parle`() {
        val watcher = SidewalkWatcher()

        repeat(2) { watcher.onPosition(SidewalkPosition.Ahead, it * 200L) }
        assertNull(watcher.onPosition(SidewalkPosition.Across, 400L))
        val cue = watcher.onPosition(SidewalkPosition.Across, 600L)!!

        assertEquals("Trottoir en face.", cue.phrase)
        assertEquals(SidewalkPosition.Across, cue.position)
    }

    @Test
    fun `trou de frame ne fait pas repeter`() {
        val watcher = SidewalkWatcher()

        repeat(2) { watcher.onPosition(SidewalkPosition.Right, it * 200L) }
        assertNull(watcher.onPosition(SidewalkPosition.Absent, 400L))
        assertNull("reconfirmation", watcher.onPosition(SidewalkPosition.Right, 600L))
        assertNull("déjà dit", watcher.onPosition(SidewalkPosition.Right, 800L))
    }

    @Test
    fun `positif isole ne parle pas`() {
        val watcher = SidewalkWatcher()

        assertNull(watcher.onPosition(SidewalkPosition.Ahead, 0L))
        assertNull(watcher.onPosition(SidewalkPosition.Left, 200L))
        assertNull(watcher.lastAlertMs)
    }

    @Test
    fun `absent et null muets`() {
        val watcher = SidewalkWatcher()

        assertNull(watcher.onPosition(SidewalkPosition.Absent, 0L))
        assertNull(watcher.onPosition(null, 200L))
        assertNull(watcher.lastAlertMs)
    }

    @Test
    fun `anglais`() {
        val watcher = SidewalkWatcher()

        watcher.onPosition(SidewalkPosition.Right, 0L, SpeechLanguage.En)
        val cue = watcher.onPosition(SidewalkPosition.Right, 200L, SpeechLanguage.En)!!

        assertEquals("Sidewalk on the right.", cue.phrase)
    }

    @Test
    fun `en face en anglais`() {
        val watcher = SidewalkWatcher()

        watcher.onPosition(SidewalkPosition.Across, 0L, SpeechLanguage.En)
        val cue = watcher.onPosition(SidewalkPosition.Across, 200L, SpeechLanguage.En)!!

        assertEquals("Sidewalk across the street.", cue.phrase)
    }

    @Test
    fun `reset oublie tout`() {
        val watcher = SidewalkWatcher()

        repeat(2) { watcher.onPosition(SidewalkPosition.Ahead, it * 200L) }
        watcher.reset()

        assertNull(watcher.lastAlertMs)
        assertNull(watcher.onPosition(SidewalkPosition.Ahead, 1_000L))
    }
}
