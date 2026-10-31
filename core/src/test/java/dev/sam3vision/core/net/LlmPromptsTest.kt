package dev.sam3vision.core.net

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les consignes du modèle sont toujours en anglais : la réponse est lue telle
 * quelle, elle doit donc être en anglais.
 */
class LlmPromptsTest {

    @Test
    fun `la consigne de question demande l anglais meme en mode francais`() {
        assertTrue(LlmPrompts.askSystem(SpeechLanguage.Fr).contains("English"))
        assertFalse(LlmPrompts.askSystem(SpeechLanguage.Fr).contains("français"))
        assertTrue(LlmPrompts.askSystem(SpeechLanguage.En).contains("English"))
        assertTrue(LlmPrompts.askMemorySystem(SpeechLanguage.En).contains("English"))
    }

    @Test
    fun `la consigne de description garde le meme schema json`() {
        listOf(SpeechLanguage.Fr, SpeechLanguage.En).forEach { language ->
            val prompt = LlmPrompts.describeSystem(language)
            assertTrue(prompt.contains("\"description\""))
            assertTrue(prompt.contains("\"tags\""))
            assertTrue(prompt.contains("English"))
        }
    }

    @Test
    fun `la demande de description est en anglais`() {
        assertEquals("Describe this image.", LlmPrompts.describeUser(emptyList(), SpeechLanguage.Fr))
        assertEquals("Describe this image.", LlmPrompts.describeUser(emptyList(), SpeechLanguage.En))
        assertTrue(LlmPrompts.describeUser(listOf("table"), SpeechLanguage.En).contains("Describe this image."))
    }

    @Test
    fun `la consigne du direct nomme les objets et la description precedente`() {
        val en = LlmPrompts.liveUser(listOf("person"), "A chair.", SpeechLanguage.En)
        assertTrue(en.contains("Tracked objects"))
        assertTrue(en.contains("person"))
        assertTrue(en.contains("Previous description"))
        assertTrue(en.contains("A chair."))

        val fr = LlmPrompts.liveUser(listOf("person"), "A chair.", SpeechLanguage.Fr)
        assertTrue(fr.contains("Tracked objects"))
        assertTrue(fr.contains("Previous description"))
    }

    @Test
    fun `l'en-tete du contexte est en anglais`() {
        assertTrue(LlmPrompts.contextHeader(SpeechLanguage.Fr).contains("Context observed by the camera:"))
        assertTrue(LlmPrompts.contextHeader(SpeechLanguage.En).contains("Context"))
    }

    @Test
    fun `chaque consigne rappelle que les blocs sont des donnees`() {
        val fr = listOf(
            LlmPrompts.describeSystem(SpeechLanguage.Fr),
            LlmPrompts.liveSystem(SpeechLanguage.Fr),
            LlmPrompts.askSystem(SpeechLanguage.Fr),
            LlmPrompts.askMemorySystem(SpeechLanguage.Fr),
        )
        fr.forEach { assertTrue("règle de donnée absente : $it", it.contains("untrusted data")) }

        val en = listOf(
            LlmPrompts.describeSystem(SpeechLanguage.En),
            LlmPrompts.liveSystem(SpeechLanguage.En),
            LlmPrompts.askSystem(SpeechLanguage.En),
            LlmPrompts.askMemorySystem(SpeechLanguage.En),
        )
        en.forEach { assertTrue("règle de donnée absente : $it", it.contains("untrusted data")) }
    }
}
