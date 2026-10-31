package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import java.util.Locale

/** Pilote TTS de test partagé : enregistre les alertes de sécurité (texte + pitch). */
internal class SafetyTestDriver : TtsDriver {
    data class Call(val text: String, val pitch: Float)

    val calls = mutableListOf<Call>()
    override val speaking: Boolean get() = false

    override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
        calls += Call(text, pitch)
        return true
    }

    override fun stop() = Unit

    override fun shutdown() = Unit
}

/** Voix de sécurité de test : alertes vers [driver], coupes comptées dans [stops]. */
internal fun testSafetyVoice(
    driver: SafetyTestDriver = SafetyTestDriver(),
    stops: MutableList<Unit> = mutableListOf(),
): SafetyVoice = SafetyVoice(
    player = SpeechPlayer(File(Files.createTempDirectory("securite-test").toFile(), "play")),
    system = SystemTtsEngine(driver),
    settings = { Settings.Default },
    stopCurrent = { stops += Unit },
)
