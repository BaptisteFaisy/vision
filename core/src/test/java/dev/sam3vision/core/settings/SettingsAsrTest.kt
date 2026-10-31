package dev.sam3vision.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les clés de dictée (Meta ASR) traversent le codec du fichier privé, sans casser un ancien fichier. */
class SettingsAsrTest {

    @Test
    fun `codec conserve base cle et modele de la dictee`() {
        val settings = Settings.Default.copy(
            asrBaseUrl = "https://api.meta.ai/v1",
            asrApiKey = "sk-asr",
            asrModel = "muse-voice-transcribe-1.0",
        )

        val relu = SettingsCodec.decode(SettingsCodec.encode(settings))

        assertEquals("https://api.meta.ai/v1", relu.asrBaseUrl)
        assertEquals("sk-asr", relu.asrApiKey)
        assertEquals("muse-voice-transcribe-1.0", relu.asrModel)
        assertTrue(relu.asrConfigured)
        assertTrue(relu.sttConfigured)
    }

    @Test
    fun `ancien fichier sans cles asr reprend les defauts`() {
        val legacy = "llmBaseUrl=x\nllmApiKey=y\nllmModel=m\nsamBaseUrl=\n" +
            "ttsApiKey=sk-tts\nttsVoiceId=v\nttsAutoplay=false\n"

        val relu = SettingsCodec.decode(legacy)

        assertEquals(Settings.DEFAULT_ASR_BASE_URL, relu.asrBaseUrl)
        assertEquals(Settings.DEFAULT_ASR_MODEL, relu.asrModel)
        assertEquals("", relu.asrApiKey)
        assertFalse(relu.asrConfigured)
        assertTrue("le repli ElevenLabs suffit pour dicter", relu.sttConfigured)
    }

    @Test
    fun `codec conserve le drapeau temps réel`() {
        val settings = Settings.Default.copy(asrApiKey = "sk-asr", museRealtime = false)

        val relu = SettingsCodec.decode(SettingsCodec.encode(settings))

        assertFalse(relu.museRealtime)
        assertFalse("drapeau coupé : pas de stream", relu.museRealtimeReady)
        assertTrue("le batch Meta reste configuré", relu.asrConfigured)
    }

    @Test
    fun `ancien fichier sans drapeau stream par défaut avec la dictée Meta`() {
        val legacy = "llmBaseUrl=x\nllmApiKey=y\nllmModel=m\nsamBaseUrl=\n" +
            "asrApiKey=sk-asr\n"

        val relu = SettingsCodec.decode(legacy)

        assertTrue(relu.museRealtime)
        assertTrue(relu.museRealtimeReady)
        assertFalse(Settings.Default.museRealtimeReady)
    }

    @Test
    fun `sans dictée Meta pas de stream même avec la clé ElevenLabs`() {
        val settings = Settings.Default.copy(ttsApiKey = "sk-tts")

        assertFalse("le stream est Meta, pas Scribe", settings.museRealtimeReady)
        assertTrue("le batch Scribe reste un repli", settings.sttConfigured)
    }
}
