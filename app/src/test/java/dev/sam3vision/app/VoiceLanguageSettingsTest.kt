package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** La langue de la voix est un réglage comme les autres : écriture bornée et phrase de test. */
class VoiceLanguageSettingsTest {

    @Test
    fun `choisir la langue n'ecrit que ce reglage`() {
        val draft = Settings.Default.copy(ttsVoiceId = "voix-42")
        val choisi = pickVoiceLanguage(draft, SpeechLanguage.Fr)

        assertEquals(SpeechLanguage.Fr, choisi.speechLanguage)
        assertEquals("voix-42", choisi.ttsVoiceId)
    }

    @Test
    fun `la voix parle anglais par defaut des reglages`() {
        assertEquals(SpeechLanguage.En, Settings.Default.speechLanguage)
    }

    @Test
    fun `la phrase de test suit la langue`() {
        assertEquals("Test de voix SAM Vision.", testVoiceText(SpeechLanguage.Fr))
        assertEquals("This is the SAM Vision voice.", testVoiceText(SpeechLanguage.En))
    }

    @Test
    fun `chaque langue a un libelle et un code`() {
        SpeechLanguage.entries.forEach { language ->
            assertTrue(language.label.isNotBlank())
            assertTrue(language.code in setOf("fr", "en"))
            assertEquals(language, SpeechLanguage.fromName(language.name))
        }
    }
}
