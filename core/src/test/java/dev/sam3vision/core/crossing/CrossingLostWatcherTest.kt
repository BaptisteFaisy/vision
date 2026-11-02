package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Perte « No crosswalk. » : passage suivi invisible depuis 3 s en Traversée, dite une fois. */
class CrossingLostWatcherTest {

    @Test
    fun `phrase perte EN FR`() {
        assertEquals("No crosswalk.", CrossingText.lost())
        assertEquals("No crosswalk.", CrossingText.lost(SpeechLanguage.En))
        assertEquals("Passage perdu.", CrossingText.lost(SpeechLanguage.Fr))
    }

    @Test
    fun `delai perte 3 secondes`() {
        assertEquals(3_000L, CrossingGuidance.LOST_DELAY_MS)
    }

    @Test
    fun `silence tant que le passage est vu`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(true, 0L))
        assertNull(watcher.onFrame(true, 10_000L))
        assertNull(watcher.lastCue)
    }

    @Test
    fun `silence avant 3 secondes d absence`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(false, 1_000L))
        assertNull(watcher.onFrame(false, 2_000L))
        assertNull(watcher.onFrame(false, 3_999L))
    }

    @Test
    fun `annonce a 3 secondes pile puis une seule fois`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(false, 1_000L))
        assertEquals("No crosswalk.", watcher.onFrame(false, 4_000L)?.phrase)
        assertEquals("No crosswalk.", watcher.lastCue?.phrase)
        assertNull(watcher.onFrame(false, 5_000L))
        assertNull(watcher.onFrame(false, 30_000L))
    }

    @Test
    fun `retour du passage re-arme`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(false, 0L))
        assertEquals("No crosswalk.", watcher.onFrame(false, 3_000L)?.phrase)
        assertNull(watcher.onFrame(true, 4_000L))
        assertNull(watcher.onFrame(false, 5_000L))
        assertEquals("No crosswalk.", watcher.onFrame(false, 8_000L)?.phrase)
    }

    @Test
    fun `passage qui clignote ne fait pas parler`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(false, 0L))
        assertNull(watcher.onFrame(false, 2_000L))
        assertNull(watcher.onFrame(true, 2_500L))
        assertNull(watcher.onFrame(false, 3_000L))
        assertNull(watcher.onFrame(false, 5_000L))
        assertNull(watcher.lastCue)
    }

    @Test
    fun `reset re-arme`() {
        val watcher = CrossingLostWatcher()
        assertNull(watcher.onFrame(false, 0L))
        assertEquals("No crosswalk.", watcher.onFrame(false, 3_000L)?.phrase)
        watcher.reset()
        assertNull(watcher.lastCue)
        assertNull(watcher.onFrame(false, 4_000L))
        assertEquals("No crosswalk.", watcher.onFrame(false, 7_000L)?.phrase)
    }
}
