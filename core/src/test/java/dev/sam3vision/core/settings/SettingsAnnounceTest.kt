package dev.sam3vision.core.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Le réglage « annoncer la position des objets » survit au codec et reste actif par défaut. */
class SettingsAnnounceTest {

    @Test
    fun `annonce des positions active par defaut`() {
        assertTrue(Settings.Default.ttsAnnouncePositions)
        assertTrue(Settings.Default.copy(ttsApiKey = "sk_test").announcePositions)
    }

    @Test
    fun `sans cle elevenlabs rien n est annonce`() {
        val sansCle = Settings.Default.copy(ttsAnnouncePositions = true, ttsApiKey = "")

        assertFalse(sansCle.announcePositions)
    }

    @Test
    fun `codec conserve la bascule coupee`() {
        val coupe = Settings.Default.copy(ttsApiKey = "sk_test", ttsAnnouncePositions = false)

        val relu = SettingsCodec.decode(SettingsCodec.encode(coupe))

        assertFalse(relu.ttsAnnouncePositions)
        assertFalse(relu.announcePositions)
    }

    @Test
    fun `ancien fichier sans la cle garde l annonce active`() {
        val legacy = "llmBaseUrl=https://api.inferhub.dev/v1\nllmApiKey=sk-llm\nttsApiKey=sk_tts\n"

        val relu = SettingsCodec.decode(legacy)

        assertTrue(relu.ttsAnnouncePositions)
        assertTrue(relu.announcePositions)
    }
}