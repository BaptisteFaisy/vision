package dev.sam3vision.core.stairs

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chaînes MP3 des comptes de marches : cardinal + unité, anglais seul
 * (le pack ne porte que des clips EN), vide hors pack (silence).
 */
class StairChainTest {

    @Test
    fun `compte = cardinal + unite, singulier 1`() {
        assertEquals(listOf("c_5", "c_steps"), StairChain.countClips(5, SpeechLanguage.En))
        assertEquals(listOf("c_1", "c_step"), StairChain.countClips(1, SpeechLanguage.En))
        assertEquals(listOf("c_20", "c_steps"), StairChain.countClips(20, SpeechLanguage.En))
    }

    @Test
    fun `compte rendu vide hors pack`() {
        assertEquals(emptyList<String>(), StairChain.countClips(0, SpeechLanguage.En))
        assertEquals(emptyList<String>(), StairChain.countClips(21, SpeechLanguage.En))
        assertEquals(emptyList<String>(), StairChain.countClips(5, SpeechLanguage.Fr))
    }

    @Test
    fun `compte a rebours = cardinal + steps left`() {
        assertEquals(
            listOf("c_5", "c_steps_left"),
            StairChain.countdownClips(5, SpeechLanguage.En),
        )
    }

    @Test
    fun `compte a rebours vide hors pack`() {
        assertEquals(emptyList<String>(), StairChain.countdownClips(1, SpeechLanguage.En))
        assertEquals(emptyList<String>(), StairChain.countdownClips(21, SpeechLanguage.En))
        assertEquals(emptyList<String>(), StairChain.countdownClips(5, SpeechLanguage.Fr))
    }

    @Test
    fun `chaine du compte reconnue, le reste non`() {
        assertTrue(StairChain.isCountChain(listOf("c_5", "c_steps")))
        assertTrue(StairChain.isCountChain(listOf("c_1", "c_step")))
        assertTrue(StairChain.isCountChain(listOf("c_20", "c_steps")))
        assertFalse(StairChain.isCountChain(listOf("c_5", "c_steps_left")))
        assertFalse(StairChain.isCountChain(listOf("c_1", "c_steps")))
        assertFalse(StairChain.isCountChain(listOf("c_0", "c_steps")))
        assertFalse(StairChain.isCountChain(listOf("c_21", "c_steps")))
        assertFalse(StairChain.isCountChain(listOf("c_5")))
        assertFalse(StairChain.isCountChain(listOf("human_person", "clock_3")))
        assertFalse(StairChain.isCountChain(emptyList()))
    }
}
