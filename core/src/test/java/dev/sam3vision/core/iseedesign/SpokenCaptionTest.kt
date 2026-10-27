package dev.sam3vision.core.iseedesign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenCaptionTest {
    private val text = "There is a chair in front of you and a wooden table near the door on your left."

    @Test
    fun `tous les passages d'une longue reponse defilent sans perdre de mot`() {
        val cues = (0L..8_000L step 20L).mapNotNull {
            spokenCaption(text, SpeechPosition(it, 8_000L))?.text
        }.distinct()
        assertEquals(text, cues.joinToString(" "))
        assertTrue(cues.size > 3)
        assertTrue(cues.all { it.split(" ").size <= 5 && it.length <= 32 })
    }

    @Test
    fun `la duree du media pilote le passage meme en lecture acceleree`() {
        val halfway = spokenCaption(text, SpeechPosition(4_000L, 8_000L))
        val early = spokenCaption(text, SpeechPosition(1_000L, 8_000L))
        assertTrue(halfway?.text != early?.text)
        assertEquals(halfway, spokenCaption(text, SpeechPosition(2_000L, 4_000L)))
        assertNull(spokenCaption(text, null))
    }

    @Test
    fun `la dictee montre les mots recents et normalise les espaces`() {
        val cue = spokenCaption("one  two\nthree four five six seven", null, live = true)
        assertEquals("six seven", cue?.text)
        assertEquals(2, cue?.shown)
        assertNull(spokenCaption(" \n ", null, live = true))
    }
}
