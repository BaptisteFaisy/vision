package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Voix du compteur de marches : le compte est dit à la détection puis à
 * chaque changement, au plus toutes les 300 ms — jamais en boucle.
 */
class StairCountVoiceTest {

    @Test
    fun `premiere detection dit le compte`() {
        assertEquals("5 marches.", StairCountVoice().onTick(true, 5, 1_000L))
    }

    @Test
    fun `meme compte ne se repete pas`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L))
        assertNull(voice.onTick(true, 5, 1_100L))
        assertNull(voice.onTick(true, 5, 10_000L))
    }

    @Test
    fun `compte change sous 300 ms attend son tour`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L))
        assertNull(voice.onTick(true, 6, 1_299L))
        assertEquals("6 marches.", voice.onTick(true, 6, 1_300L))
    }

    @Test
    fun `escalier perdu puis revu redit le compte`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L))
        assertNull(voice.onTick(false, 0, 1_100L))
        assertEquals("5 marches.", voice.onTick(true, 5, 1_400L))
    }

    @Test
    fun `compte zero = non compte, silence`() {
        val voice = StairCountVoice()
        assertNull(voice.onTick(true, 0, 1_000L))
        assertEquals("4 marches.", voice.onTick(true, 4, 1_100L))
    }

    @Test
    fun `reset oublie compte et instant`() {
        val voice = StairCountVoice()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_000L))
        voice.reset()
        assertEquals("5 marches.", voice.onTick(true, 5, 1_100L))
    }

    @Test
    fun `phrase singulier et pluriel fr-en`() {
        assertEquals("1 marche.", StairText.stepCount(1, SpeechLanguage.Fr))
        assertEquals("5 marches.", StairText.stepCount(5, SpeechLanguage.Fr))
        assertEquals("1 step.", StairText.stepCount(1, SpeechLanguage.En))
        assertEquals("5 steps.", StairText.stepCount(5, SpeechLanguage.En))
    }
}
