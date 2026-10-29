package dev.sam3vision.core.hazard

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mobilier proche dans le couloir : détecté, le plus proche nommé, le reste muet. */
class HazardWatchTest {

    private fun reading(label: String, meters: Float, box: List<Float>) =
        DistanceReading(trackId = -1, label = label, meters = meters, rawMeters = meters, box = box)

    /** Mobilier centré (frame 512×1138). */
    private fun furniture(label: String, meters: Float) =
        reading(label, meters, listOf(156f, 400f, 356f, 900f))

    @Test
    fun `barriere proche dans le couloir = detectee et nommee`() {
        val signal = HazardSignal.of(listOf(furniture("barrier", 0.8f)), 512, 1138)

        assertTrue(signal.detected)
        assertEquals(0.8f, signal.meters!!, 0.001f)
        assertEquals("barrier", signal.label)
    }

    @Test
    fun `plus proche gagne`() {
        val signal = HazardSignal.of(
            listOf(furniture("barrier", 2.0f), furniture("pole", 0.9f)),
            512, 1138,
        )

        assertTrue(signal.detected)
        assertEquals(0.9f, signal.meters!!, 0.001f)
        assertEquals("pole", signal.label)
    }

    @Test
    fun `hors couloir = silence`() {
        val signal = HazardSignal.of(
            listOf(reading("barrier", 0.8f, listOf(0f, 400f, 100f, 900f))),
            512, 1138,
        )

        assertFalse(signal.detected)
    }

    @Test
    fun `au-dela de trois metres = silence`() {
        val signal = HazardSignal.of(listOf(furniture("barrier", 3.5f)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `sans mesure = silence`() {
        val signal = HazardSignal.of(listOf(furniture("barrier", Float.NaN)), 512, 1138)

        assertFalse(signal.detected)
    }

    @Test
    fun `deux positifs parlent, un isole non`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier")

        assertNull(watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
        assertEquals(0.8f, cue.meters!!, 0.001f)
    }

    @Test
    fun `phrases FR EN`() {
        val barrier = HazardConcepts.nameOf("barrier")!!
        val pole = HazardConcepts.nameOf("poteau")!!
        assertEquals("Attention à la barrière.", HazardText.phrase(barrier, SpeechLanguage.Fr))
        assertEquals("Attention au poteau.", HazardText.phrase(pole, SpeechLanguage.Fr))
        assertEquals("Attention, barrier.", HazardText.phrase(barrier, SpeechLanguage.En))
    }

    @Test
    fun `cooldown tait la repetition`() {
        val watcher = HazardWatcher()
        val signal = HazardSignal(detected = true, meters = 0.8f, label = "barrier")
        watcher.onSignal(signal, 1_000L, SpeechLanguage.Fr)
        watcher.onSignal(signal, 2_000L, SpeechLanguage.Fr)

        watcher.onSignal(signal, 3_000L, SpeechLanguage.Fr)
        assertNull(watcher.onSignal(signal, 4_000L, SpeechLanguage.Fr))
        val cue = watcher.onSignal(signal, 13_000L, SpeechLanguage.Fr)

        assertEquals("Attention à la barrière.", cue!!.phrase)
    }
}
