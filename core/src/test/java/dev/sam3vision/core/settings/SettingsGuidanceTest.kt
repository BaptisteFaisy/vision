package dev.sam3vision.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le réglage « guider tout droit » survit au codec et reste actif par défaut. */
class SettingsGuidanceTest {

    @Test
    fun `guidage actif par defaut`() {
        assertTrue(Settings.Default.ttsGuidanceStraight)
        assertTrue(Settings.Default.copy(ttsApiKey = "sk_test").guideStraight)
    }

    @Test
    fun `sans cle elevenlabs rien n est guide`() {
        val sansCle = Settings.Default.copy(ttsGuidanceStraight = true, ttsApiKey = "")

        assertFalse(sansCle.guideStraight)
    }

    @Test
    fun `codec conserve la bascule coupee`() {
        val coupe = Settings.Default.copy(ttsApiKey = "sk_test", ttsGuidanceStraight = false)

        val relu = SettingsCodec.decode(SettingsCodec.encode(coupe))

        assertFalse(relu.ttsGuidanceStraight)
        assertFalse(relu.guideStraight)
    }

    @Test
    fun `ancien fichier sans la cle garde le guidage actif`() {
        val legacy = "llmBaseUrl=https://api.inferhub.dev/v1\nllmApiKey=sk-llm\nttsApiKey=sk_tts\n"

        val relu = SettingsCodec.decode(legacy)

        assertTrue(relu.ttsGuidanceStraight)
        assertTrue(relu.guideStraight)
    }
}
