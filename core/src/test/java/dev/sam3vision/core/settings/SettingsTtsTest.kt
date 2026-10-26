package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les réglages TTS sont persistés, et un ancien fichier reste lisible. */
class SettingsTtsTest {

    @Test
    fun `codec conserve cle et voix`() {
        val settings = Settings.Default.copy(ttsApiKey = "sk_test", ttsVoiceId = "voix-42")

        val relu = SettingsCodec.decode(SettingsCodec.encode(settings))

        assertEquals("sk_test", relu.ttsApiKey)
        assertEquals("voix-42", relu.ttsVoiceId)
        assertTrue(relu.ttsConfigured)
    }

    @Test
    fun `ancien fichier sans cles tts reprend les defauts`() {
        val legacy = "llmBaseUrl=https://api.inferhub.dev/v1\nllmApiKey=sk-llm\nllmModel=m\nsamBaseUrl=\n"

        val relu = SettingsCodec.decode(legacy)

        assertEquals("", relu.ttsApiKey)
        assertEquals(Settings.DEFAULT_TTS_VOICE_ID, relu.ttsVoiceId)
        assertTrue(relu.ttsAutoplay)
        assertFalse(relu.ttsConfigured)
    }

    @Test
    fun `lecture automatique conservee par le codec`() {
        val coupe = Settings.Default.copy(ttsApiKey = "sk_test", ttsAutoplay = false)

        val relu = SettingsCodec.decode(SettingsCodec.encode(coupe))

        assertFalse(relu.ttsAutoplay)
        assertEquals("sk_test", relu.ttsApiKey)
    }
}
