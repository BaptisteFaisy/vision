package dev.sam3vision.core.crossing

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arrivée « Sur le passage. » : bas touché, pieds proches ou profondeur courte — douce, 2 frames. */
class CrossingArrivalTest {

    @Test
    fun `phrase arrivee douce FR EN`() {
        assertEquals("Sur le passage.", CrossingText.arrived(SpeechLanguage.Fr))
        assertEquals("On the crosswalk.", CrossingText.arrived())
        assertEquals("On the crosswalk.", CrossingText.arrived(SpeechLanguage.En))
    }

    @Test
    fun `bas touche pixels marge 2px`() {
        assertTrue(CrossingGuidance.touchesBottom(listOf(0f, 0f, 100f, 1138f), 1138))
        assertTrue(CrossingGuidance.touchesBottom(listOf(0f, 0f, 100f, 1136f), 1138))
        assertFalse(CrossingGuidance.touchesBottom(listOf(0f, 0f, 100f, 900f), 1138))
        assertFalse(CrossingGuidance.touchesBottom(listOf(1f, 2f), 1138))
        assertFalse(CrossingGuidance.touchesBottom(listOf(0f, 0f, 100f, 1138f), 0))
    }

    @Test
    fun `bas touche normalise 098`() {
        assertTrue(CrossingGuidance.touchesBottomNormalized(listOf(0f, 0f, 1f, 0.99f)))
        assertTrue(CrossingGuidance.touchesBottomNormalized(listOf(0f, 0f, 1f, 0.98f)))
        assertFalse(CrossingGuidance.touchesBottomNormalized(listOf(0f, 0f, 1f, 0.90f)))
        assertFalse(CrossingGuidance.touchesBottomNormalized(listOf(0f)))
    }

    @Test
    fun `pieds proches 092 de la hauteur`() {
        assertTrue(CrossingGuidance.footClose(1050f, 1138))
        assertTrue(CrossingGuidance.footClose(1138f, 1138))
        assertFalse(CrossingGuidance.footClose(900f, 1138))
        assertFalse(CrossingGuidance.footClose(Float.NaN, 1138))
        assertFalse(CrossingGuidance.footClose(1100f, 0))
    }

    @Test
    fun `profondeur courte 15m max`() {
        assertTrue(CrossingGuidance.depthClose(0.5f))
        assertTrue(CrossingGuidance.depthClose(1.5f))
        assertFalse(CrossingGuidance.depthClose(1.6f))
        assertFalse(CrossingGuidance.depthClose(null))
        assertFalse(CrossingGuidance.depthClose(Float.NaN))
        assertFalse(CrossingGuidance.depthClose(-1f))
    }

    @Test
    fun `arrivee pixels OU des trois signaux`() {
        assertTrue(CrossingGuidance.isArrivedPx(listOf(0f, 0f, 100f, 1138f), 1138, null))
        assertTrue(CrossingGuidance.isArrivedPx(listOf(0f, 0f, 100f, 1050f), 1138, null))
        assertTrue(CrossingGuidance.isArrivedPx(listOf(0f, 0f, 100f, 500f), 1138, 1.0f))
        assertFalse(CrossingGuidance.isArrivedPx(listOf(0f, 0f, 100f, 500f), 1138, 5.0f))
        assertFalse(CrossingGuidance.isArrivedPx(listOf(0f, 0f, 100f, 500f), 1138, null))
    }

    @Test
    fun `arrivee normalisee bas ou profondeur`() {
        assertTrue(CrossingGuidance.isArrivedNormalized(listOf(0f, 0f, 1f, 0.99f), null))
        assertTrue(CrossingGuidance.isArrivedNormalized(listOf(0f, 0f, 1f, 0.50f), 1.0f))
        assertFalse(CrossingGuidance.isArrivedNormalized(listOf(0f, 0f, 1f, 0.50f), 5.0f))
    }

    @Test
    fun `deux frames parlent, une seule ne parle pas`() {
        val watcher = CrossingArrivalWatcher()
        assertNull(watcher.onFrame(true))
        val cue = watcher.onFrame(true)
        assertEquals("On the crosswalk.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
    }

    @Test
    fun `changement seul, pas de repetition`() {
        val watcher = CrossingArrivalWatcher()
        watcher.onFrame(true)
        watcher.onFrame(true)
        assertNull(watcher.onFrame(true))
        assertNull(watcher.onFrame(true))
    }

    @Test
    fun `negatif re-arme l arrivee`() {
        val watcher = CrossingArrivalWatcher()
        watcher.onFrame(true)
        watcher.onFrame(true)
        watcher.onFrame(false)
        assertNull(watcher.onFrame(true))
        assertEquals("On the crosswalk.", watcher.onFrame(true)?.phrase)
    }

    @Test
    fun `negatif remet le compte a zero`() {
        val watcher = CrossingArrivalWatcher()
        watcher.onFrame(true)
        watcher.onFrame(false)
        assertNull(watcher.onFrame(true))
        assertEquals("On the crosswalk.", watcher.onFrame(true)?.phrase)
    }

    @Test
    fun `reset oublie tout`() {
        val watcher = CrossingArrivalWatcher()
        watcher.onFrame(true)
        watcher.onFrame(true)
        watcher.reset()
        assertNull(watcher.lastCue)
        assertNull(watcher.onFrame(true))
        assertEquals("On the crosswalk.", watcher.onFrame(true)?.phrase)
    }

    @Test
    fun `arrivee anglaise douce`() {
        val watcher = CrossingArrivalWatcher()
        watcher.onFrame(true, SpeechLanguage.En)
        val cue = watcher.onFrame(true, SpeechLanguage.En)
        assertEquals("On the crosswalk.", cue?.phrase)
        assertFalse(cue?.urgent ?: true)
    }
}
