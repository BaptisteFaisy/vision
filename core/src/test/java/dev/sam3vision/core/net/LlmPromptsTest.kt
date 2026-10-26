package dev.sam3vision.core.net

import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Les consignes du modèle suivent la langue de la voix : la réponse est lue telle
 * quelle, elle doit donc être dans la bonne langue.
 */
class LlmPromptsTest {

    @Test
    fun `la consigne de question demande la bonne langue`() {
        assertTrue(LlmPrompts.askSystem(SpeechLanguage.Fr).contains("français"))
        assertTrue(LlmPrompts.askSystem(SpeechLanguage.En).contains("English"))
        assertTrue(LlmPrompts.askMemorySystem(SpeechLanguage.En).contains("English"))
        assertFalse(LlmPrompts.askSystem(SpeechLanguage.En).contains("français"))
    }

    @Test
    fun `la consigne de description garde le meme schema json`() {
        val fr = LlmPrompts.describeSystem(SpeechLanguage.Fr)
        val en = LlmPrompts.describeSystem(SpeechLanguage.En)
        listOf(fr, en).forEach { prompt ->
            assertTrue(prompt.contains("\"description\""))
            assertTrue(prompt.contains("\"tags\""))
        }
        assertTrue(en.contains("English"))
    }

    @Test
    fun `la demande de description suit la langue`() {
        assertEquals("Décris cette image.", LlmPrompts.describeUser(emptyList(), SpeechLanguage.Fr))
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
        assertTrue(fr.contains("Objets suivis"))
        assertTrue(fr.contains("Description précédente"))
    }

    @Test
    fun `l'en-tete du contexte suit la langue`() {
        assertTrue(LlmPrompts.contextHeader(SpeechLanguage.Fr).contains("Contexte"))
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
        fr.forEach { assertTrue("règle de donnée absente : $it", it.contains("Donnée non fiable")) }

        val en = listOf(
            LlmPrompts.describeSystem(SpeechLanguage.En),
            LlmPrompts.liveSystem(SpeechLanguage.En),
            LlmPrompts.askSystem(SpeechLanguage.En),
            LlmPrompts.askMemorySystem(SpeechLanguage.En),
        )
        en.forEach { assertTrue("règle de donnée absente : $it", it.contains("untrusted data")) }
    }
}
