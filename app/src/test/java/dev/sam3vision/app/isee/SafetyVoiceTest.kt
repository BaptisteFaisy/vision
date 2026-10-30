package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Faux pilote : enregistre les appels, parole programmable. */
private class FakeSafetyDriver(var speakingValue: Boolean = false) : TtsDriver {
    data class Call(val text: String, val flush: Boolean, val locale: Locale, val rate: Float)

    val calls = mutableListOf<Call>()

    override val speaking: Boolean get() = speakingValue

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float): Boolean {
        calls += Call(text, flush, locale, rate)
        return true
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/**
 * Voix de sécurité : l'alerte coupe la lecture ElevenLabs et parle aussitôt
 * au TTS système — sans condition de silence, même quand l'app parlait déjà.
 */
class SafetyVoiceTest {

    private fun safety(
        driver: FakeSafetyDriver,
        stops: MutableList<Unit>,
        language: SpeechLanguage = SpeechLanguage.En,
    ) = SafetyVoice(
        player = SpeechPlayer(File(Files.createTempDirectory("securite").toFile(), "play")),
        system = SystemTtsEngine(driver),
        settings = { Settings.Default.copy(speechLanguage = language, ttsSpeed = 1f) },
        stopCurrent = { stops += Unit },
    )

    @Test
    fun `alerte coupe la lecture et parle aussitot en flush`() {
        val driver = FakeSafetyDriver()
        val stops = mutableListOf<Unit>()

        safety(driver, stops).speak("Stop, car moving.")

        assertEquals(listOf(Unit), stops)
        assertEquals(1, driver.calls.size)
        assertEquals("Stop, car moving.", driver.calls.single().text)
        assertTrue(driver.calls.single().flush)
        assertEquals(Locale.ENGLISH, driver.calls.single().locale)
    }

    @Test
    fun `alerte sur alerte - mise en file, jamais coupee`() {
        val driver = FakeSafetyDriver(speakingValue = true)
        val stops = mutableListOf<Unit>()

        safety(driver, stops).speak("Stop, step up.")

        assertEquals(listOf(Unit), stops)
        assertEquals(1, driver.calls.size)
        assertFalse(driver.calls.single().flush)
    }

    @Test
    fun `blanc ignore - ni coupe ni parole`() {
        val driver = FakeSafetyDriver()
        val stops = mutableListOf<Unit>()

        safety(driver, stops).speak("   ")

        assertTrue(stops.isEmpty())
        assertTrue(driver.calls.isEmpty())
    }

    @Test
    fun `locale suit la langue de la voix`() {
        val driver = FakeSafetyDriver()
        val stops = mutableListOf<Unit>()

        safety(driver, stops, SpeechLanguage.Fr).speak("Stop, voiture en mouvement.")

        assertEquals(Locale.FRENCH, driver.calls.single().locale)
    }
}
