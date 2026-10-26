package dev.sam3vision.core.settings

import dev.sam3vision.core.model.ObjectLimits
import dev.sam3vision.core.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsCodecTest {

    @Test
    fun `le plafond d'objets survit a un aller-retour`() {
        val saved = Settings.Default.copy(maxObjects = 9)
        assertEquals(9, SettingsCodec.decode(SettingsCodec.encode(saved)).maxObjects)
    }

    @Test
    fun `un ancien fichier sans plafond reprend le defaut`() {
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("maxObjects=") }
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(legacy.joinToString("\n")).maxObjects)
    }

    @Test
    fun `un plafond aberrant est borne a la lecture`() {
        val defaultLine = "maxObjects=${ObjectLimits.DEFAULT_ON_SCREEN}"
        val broken = SettingsCodec.encode(Settings.Default).replace(defaultLine, "maxObjects=999")
        assertEquals(ObjectLimits.MAX_ON_SCREEN, SettingsCodec.decode(broken).maxObjects)
        val text = SettingsCodec.encode(Settings.Default).replace(defaultLine, "maxObjects=abc")
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, SettingsCodec.decode(text).maxObjects)
    }

    @Test
    fun `le mode vision seule survit a un aller-retour`() {
        // Le codec est la sérialisation de PrivateFileSettingsStore : défaut true
        // (SAM appelé pendant le direct), puis false relu tel quel après bascule.
        assertEquals(true, SettingsCodec.decode("").liveSamEnabled)
        assertEquals(true, SettingsCodec.decode(SettingsCodec.encode(Settings.Default)).liveSamEnabled)

        val vision = Settings.Default.copy(liveSamEnabled = false)
        assertEquals(false, SettingsCodec.decode(SettingsCodec.encode(vision)).liveSamEnabled)
    }

    @Test
    fun `la langue de la voix survit a un aller-retour`() {
        val french = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr)
        assertEquals(SpeechLanguage.Fr, SettingsCodec.decode(SettingsCodec.encode(french)).speechLanguage)
        // Un fichier écrit avant la bascule garde la langue par défaut (anglais).
        val legacy = SettingsCodec.encode(Settings.Default).lines().filterNot { it.startsWith("speechLanguage=") }
        assertEquals(
            SpeechLanguage.DEFAULT,
            SettingsCodec.decode(legacy.joinToString("\n")).speechLanguage,
        )
    }

    @Test
    fun `l'acces objectsOnScreen borne toujours le reglage`() {
        assertEquals(ObjectLimits.MAX_ON_SCREEN, Settings.Default.copy(maxObjects = 50).objectsOnScreen)
        assertEquals(ObjectLimits.DEFAULT_ON_SCREEN, Settings.Default.objectsOnScreen)
    }
}
