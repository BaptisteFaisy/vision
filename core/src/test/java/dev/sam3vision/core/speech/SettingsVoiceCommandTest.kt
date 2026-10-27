package dev.sam3vision.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsVoiceCommandTest {

    @Test
    fun `settings seul ouvre les reglages`() {
        assertTrue(SettingsVoiceCommand.isSettingsOrder("settings"))
    }

    @Test
    fun `open settings ouvre les reglages`() {
        assertTrue(SettingsVoiceCommand.isSettingsOrder("open settings"))
    }

    @Test
    fun `reglages et parametres en francais ouvrent les reglages`() {
        assertTrue(SettingsVoiceCommand.isSettingsOrder("ouvre les réglages"))
        assertTrue(SettingsVoiceCommand.isSettingsOrder("va dans les paramètres"))
    }

    @Test
    fun `une question ordinaire ne declenche pas`() {
        assertFalse(SettingsVoiceCommand.isSettingsOrder("décris-moi la pièce"))
        assertFalse(SettingsVoiceCommand.isSettingsOrder("what do you see"))
    }
}
