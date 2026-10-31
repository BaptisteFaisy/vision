package dev.sam3vision.app

import dev.sam3vision.core.settings.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique pure de la voix : refus locaux, état d'un bouton, défauts de réglages. */
class TtsLogicTest {

    private val configured = Settings.Default.copy(ttsApiKey = "sk_test")

    @Test
    fun `texte vide et cle absente refuses avant tout reseau`() {
        assertEquals(SPEAK_TEXT_MISSING, speakRefusal("  ", true))
        assertEquals(SPEAK_NO_KEY, speakRefusal("Bonjour.", false))
        assertNull(speakRefusal("Bonjour.", true))
    }

    @Test
    fun `une phrase non vide avec cle est acceptee`() {
        assertTrue(configured.ttsConfigured)
        assertNull(speakRefusal("Bonjour.", configured.ttsConfigured))
    }

    @Test
    fun `seule la phrase en cours est active`() {
        val state = SpeakState.Synthesizing("Bonjour.")
        assertTrue(speakIsActive(state, "Bonjour. "))
        assertFalse(speakIsActive(state, "Autre phrase."))
        assertFalse(speakIsActive(SpeakState.Done("Bonjour."), "Bonjour."))
        assertFalse(speakIsActive(SpeakState.Idle, "Bonjour."))
    }

    @Test
    fun `libelle suit l etat et la phrase`() {
        assertNull(speakLabel(SpeakState.Idle, "Bonjour."))
        assertEquals("voice running…", speakLabel(SpeakState.Synthesizing("Bonjour."), "Bonjour."))
        assertEquals("playing…", speakLabel(SpeakState.Playing("Bonjour."), "Bonjour."))
        assertEquals("voice OK", speakLabel(SpeakState.Done("Bonjour."), "Bonjour."))
        assertNull(speakLabel(SpeakState.Error("Autre.", "402"), "Bonjour."))
        assertEquals("402", speakLabel(SpeakState.Error("Bonjour.", "402"), "Bonjour."))
    }

    @Test
    fun `lecture automatique seulement si interrupteur et cle`() {
        val actif = Settings.Default.copy(ttsApiKey = "sk_test", ttsAutoplay = true)
        assertTrue(actif.ttsAutoRead)
        assertFalse(actif.copy(ttsAutoplay = false).ttsAutoRead)
        assertFalse(Settings.Default.copy(ttsAutoplay = true, ttsApiKey = "").ttsAutoRead)
    }

    @Test
    fun `voix vide completee par le defaut`() {
        val sansVoix = Settings.Default.copy(ttsVoiceId = "")

        assertEquals(Settings.DEFAULT_TTS_VOICE_ID, withTtsDefaults(sansVoix).ttsVoiceId)
        assertEquals("voix-42", withTtsDefaults(sansVoix.copy(ttsVoiceId = "voix-42")).ttsVoiceId)
    }

    @Test
    fun `voix par defaut est Laure`() {
        assertEquals(Settings.LAURE_VOICE_ID, Settings.Default.ttsVoiceId)
        assertEquals("3b8fXc91YHS1i2DYAlBQ", Settings.DEFAULT_TTS_VOICE_ID)
    }

    @Test
    fun `cran de vitesse borne sans derive flottante`() {
        assertEquals(2.0f, bumpTtsSpeed(1.9f, 0.1f), 0.001f)
        assertEquals(1.8f, bumpTtsSpeed(1.9f, -0.1f), 0.001f)
        assertEquals(2.0f, bumpTtsSpeed(2.0f, 0.1f), 0.001f)
        assertEquals(0.5f, bumpTtsSpeed(0.5f, -0.1f), 0.001f)
    }

    @Test
    fun `vitesse affichee en anglais`() {
        assertEquals("×1.9", formatTtsSpeed(1.9f))
        assertEquals("×2", formatTtsSpeed(2.0f))
        assertEquals("×0.5", formatTtsSpeed(0.1f))
    }
}