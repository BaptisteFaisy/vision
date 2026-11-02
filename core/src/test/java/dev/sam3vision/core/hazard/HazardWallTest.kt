package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Murs : jamais nommés, mot d'évitement continu comme les poteaux (ordre user du 02/11). */
class HazardWallTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Danger centré (frame 512×1138). */
    private fun danger(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `mur proche = mot d'evitement des la 1re frame, jamais nomme`() {
        val signal = HazardSignal.of(listOf(danger("wall", 1.5f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals("wall", signal.label)

        val watcher = HazardWatcher()
        val manual = HazardSignal(detected = true, meters = 1.5f, label = "mur", centerX = 0.5f)

        val cue = watcher.onSignal(manual, 1_000L, SpeechLanguage.Fr)

        assertEquals("Droite.", cue!!.phrase)
        assertTrue(cue.urgent)
        assertEquals(HazardKind.OTHER, cue.kind)
        assertNull(cue.safetyPhrase)
        assertEquals(4, cue.level)
        assertEquals("wall-avoid", cue.antiRepeatKey)
    }

    @Test
    fun `mur lateral = cote oppose, FR EN`() {
        val watcher = HazardWatcher()
        val right = HazardSignal(detected = true, meters = 1.5f, label = "wall", centerX = 0.8f)
        val left = HazardSignal(detected = true, meters = 1.5f, label = "wall", centerX = 0.2f)

        assertEquals("Gauche.", watcher.onSignal(right, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertEquals("Right.", HazardWatcher().onSignal(left, 1_000L, SpeechLanguage.En)!!.phrase)
    }

    @Test
    fun `mot continu de mur repete a 300 ms, immediat au changement`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 1.5f, label = "mur")

        assertEquals("Droite.", watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)!!.phrase)
        assertNull(watcher.onSignal(signal, 1_299L, SpeechLanguage.Fr))
        assertEquals("Droite.", watcher.onSignal(signal, 1_300L, SpeechLanguage.Fr)!!.phrase)
        val moved = HazardSignal(detected = true, meters = 1.5f, label = "mur", centerX = 0.8f)
        assertEquals("Gauche.", watcher.onSignal(moved, 1_350L, SpeechLanguage.Fr)!!.phrase)
    }
}
