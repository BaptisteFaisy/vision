package dev.sam3vision.app.speech

import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rappel périodique ([VoiceNavSpeaker.onRepeat], guidage 5 s) : redit
 * volontaire hors porte anti-répétition, au silence seul, jamais devant
 * la parole du user. Même faux pilote que [VoiceNavSpeakerTest].
 */
class VoiceNavSpeakerRepeatTest {

    private var now = 0L

    /** Le user parle-t-il ? Muté par les tests de priorité. */
    private var userSpeaking = false

    private fun speaker(
        driver: FakeSpeakerDriver = FakeSpeakerDriver(),
        settings: Settings = Settings.Default.copy(speechLanguage = SpeechLanguage.Fr),
    ) = VoiceNavSpeaker(
        engine = SystemTtsEngine(driver),
        settings = { settings },
        clock = { now },
        userSpeaking = { userSpeaking },
    )

    @Test
    fun `rappel parle au silence`() {
        val driver = FakeSpeakerDriver()

        val phrase = speaker(driver).onRepeat("Turn left.")

        assertEquals("Turn left.", phrase)
        assertEquals(1, driver.calls.size)
        assertEquals(FakeSpeakerDriver.Call("Turn left.", false, Locale.FRENCH, Settings.Default.ttsSpeed), driver.calls[0])
    }

    @Test
    fun `rappel redit deux fois la meme phrase`() {
        val driver = FakeSpeakerDriver()
        val voice = speaker(driver)

        assertEquals("Turn left.", voice.onRepeat("Turn left."))
        assertEquals("Turn left.", voice.onRepeat("Turn left."))
        assertEquals(2, driver.calls.size)
    }

    @Test
    fun `rappel tait quand le user parle`() {
        val driver = FakeSpeakerDriver()
        userSpeaking = true

        assertNull(speaker(driver).onRepeat("Turn left."))
        assertEquals(0, driver.calls.size)
    }

    @Test
    fun `rappel tait quand le moteur parle`() {
        val driver = FakeSpeakerDriver()
        driver.speakingValue = true

        assertNull(speaker(driver).onRepeat("Turn left."))
        assertEquals(0, driver.calls.size)
    }

    @Test
    fun `rappel blanc garde le silence`() {
        assertNull(speaker().onRepeat("  "))
    }
}
