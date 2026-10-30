package dev.sam3vision.core.stairs

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.LivePrompts
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rampe nommée par SAM : détectée au cadran, annoncée en douceur. */
class HandrailWatcherTest {

    private fun reading(label: String, meters: Float, x1: Float = 30f, x2: Float = 70f) =
        DistanceReading(1, label, meters, meters, listOf(x1, 10f, x2, 90f))

    private val positive = HandrailSignal(detected = true, meters = 1.5f, centerX = 0.5f)

    @Test
    fun `rampe reconnue en francais et en anglais`() {
        listOf("handrail", "Handrail", "handrails", "railing", "banister", "rampe", "Rampe").forEach {
            assertTrue(it, HandrailConcepts.isHandrail(it))
        }
        assertTrue(HandrailConcepts.isHandrail("main courante"))
        assertTrue(HandrailConcepts.isHandrail("garde-corps"))
        listOf("chaise", "stairs", "escalier", "marche", "").forEach {
            assertFalse(it, HandrailConcepts.isHandrail(it))
        }
    }

    @Test
    fun `prompt rampe tient dans les plafonds`() {
        val prompts = HandrailConcepts.promptsFor()
        assertEquals("handrail", prompts.first())
        assertTrue(prompts.size <= LivePrompts.MAX)
        prompts.forEach { assertTrue(it.length <= LivePrompts.MAX_LENGTH) }
    }

    @Test
    fun `deux positifs consecutifs = annonce au cadran`() {
        val watcher = HandrailWatcher()

        assertNull(watcher.onSignal(positive, 1000L))
        val cue = watcher.onSignal(positive, 2000L)

        assertNotNull(cue)
        assertEquals("Rampe, à midi.", cue!!.phrase)
        assertEquals(1.5f, cue.meters!!, 0f)
    }

    @Test
    fun `l'heure situe l'annonce, en francais et en anglais`() {
        val watcher = HandrailWatcher()
        val right = HandrailSignal(detected = true, meters = 2f, centerX = 0.8f)

        watcher.onSignal(right, 1000L)
        val cue = watcher.onSignal(right, 2000L)
        assertEquals("Rampe, à 2 heures.", cue!!.phrase)

        val english = HandrailWatcher()
        english.onSignal(right, 1000L, SpeechLanguage.En)
        val en = english.onSignal(right, 2000L, SpeechLanguage.En)
        assertEquals("Handrail, at 2 o'clock.", en!!.phrase)
    }

    @Test
    fun `signal des lectures - proche gagne, loin et hors couloir tues`() {
        val near = HandrailSignal.of(listOf(reading("handrail", 2f)), 100, 100)
        assertTrue(near.detected)
        assertEquals(0.5f, near.centerX!!, 0.001f)

        assertFalse(HandrailSignal.of(listOf(reading("handrail", 5f)), 100, 100).detected)
        assertFalse(HandrailSignal.of(listOf(reading("handrail", 1f, 0f, 10f)), 100, 100).detected)
        assertFalse(HandrailSignal.of(listOf(reading("chaise", 1f)), 100, 100).detected)
        assertFalse(HandrailSignal.of(listOf(reading("handrail", 1f)), 0, 100).detected)
    }

    @Test
    fun `negatif remet le compte a zero, cooldown tait puis relache`() {
        val watcher = HandrailWatcher()

        watcher.onSignal(positive, 1000L)
        watcher.onSignal(HandrailSignal.none(), 2000L)
        assertNull(watcher.onSignal(positive, 3000L))
        assertNotNull(watcher.onSignal(positive, 4000L))

        val repeat = HandrailWatcher(cooldownMs = 10_000L)
        repeat.onSignal(positive, 1000L)
        assertNotNull(repeat.onSignal(positive, 2000L))
        assertNull(repeat.onSignal(positive, 3000L))
        assertNull(repeat.onSignal(positive, 4000L))
        assertNull(repeat.onSignal(positive, 5000L))
        assertNotNull(repeat.onSignal(positive, 12_001L))
    }

    @Test
    fun `reset oublie compte et annonce`() {
        val watcher = HandrailWatcher()
        watcher.onSignal(positive, 1000L)
        watcher.reset()

        assertNull(watcher.onSignal(positive, 2000L))
        assertNotNull(watcher.onSignal(positive, 3000L))
    }
}
