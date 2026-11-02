package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repli hors-ligne P2a+P2c : [ProximityVoice.speakOffline] parle via le TTS
 * système sans toucher le lecteur partagé — [onOfflineSpoken] libère le bus
 * ([VoiceBus.onSpeechIdle] en prod) ; la synthèse est plafonnée à 5 s
 * ([ApiError.Timeout] → chemin panne réseau existant). Pur JVM (faux pilote).
 */
class ProximityVoiceOfflineReleaseTest {

    private class FakeDriver(var accepts: Boolean = true) : TtsDriver {
        val heard = mutableListOf<String>()
        override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
            if (!accepts) return false
            heard += text
            return true
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override val speaking: Boolean get() = false
    }

    private class Rig(
        val driver: FakeDriver = FakeDriver(),
        var system: SystemTtsEngine? = SystemTtsEngine(driver),
        var synthesize: suspend (String) -> Result<ByteArray> = { Result.failure(ApiError.Timeout()) },
    ) {
        val dir: File = Files.createTempDirectory("voixrepli").toFile()
        val clips = VoicePack(dir, packVoiceId = { "voix-test" }, bundledClip = { null })
        var offlineCalls = 0

        fun voice(): ProximityVoice = ProximityVoice(
            player = SpeechPlayer(File(dir, "play")),
            settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
            synthesize = { _, _, text -> synthesize(text) },
            clips = clips,
            system = system,
            onOfflineSpoken = { offlineCalls++ },
        )
    }

    @Test
    fun `T-2a panne reseau GPS, dit au systeme et bus libere`() = runTest {
        val rig = Rig()

        assertTrue(rig.voice().speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(listOf("Turn left in 20 meters."), rig.driver.heard)
        assertEquals(1, rig.offlineCalls)
    }

    @Test
    fun `T-2a moteur qui refuse, bus non libere`() = runTest {
        val rig = Rig(driver = FakeDriver(accepts = false))

        assertFalse(rig.voice().speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(0, rig.offlineCalls)
    }

    @Test
    fun `T-2c synthese 60 s, rendue en 5 s virtuelles`() = runTest {
        val rig = Rig(system = null, synthesize = { delay(60_000); Result.success(byteArrayOf(1)) })
        val start = testScheduler.currentTime

        assertFalse(rig.voice().speak("Rue des Lilas.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(5_000L, testScheduler.currentTime - start)
    }

    @Test
    fun `T-2c timeout replie au systeme et libere le bus`() = runTest {
        val rig = Rig(synthesize = { delay(60_000); Result.success(byteArrayOf(1)) })
        val start = testScheduler.currentTime

        assertTrue(rig.voice().speak("Rue des Lilas.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(5_000L, testScheduler.currentTime - start)
        assertEquals(listOf("Rue des Lilas."), rig.driver.heard)
        assertEquals(1, rig.offlineCalls)
    }
}
