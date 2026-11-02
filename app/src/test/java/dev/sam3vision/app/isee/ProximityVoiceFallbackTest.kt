package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.SystemTtsEngine
import dev.sam3vision.app.speech.TtsDriver
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repli panne réseau (étape 3) : voix ElevenLabs en panne (clé, crédits,
 * cadence, timeout, réseau — [ApiError]) → la phrase part au TTS système
 * quand un moteur est câblé, jetée sinon (`panne-reseau`, comme avant).
 * Erreur non réseau : jetée même avec moteur (`synthese-echec`). Sans clé :
 * silence même avec moteur (SPEC v4 conservé). Pur JVM (faux pilote).
 */
class ProximityVoiceFallbackTest {

    private class FakeDriver(var accepts: Boolean = true) : TtsDriver {
        val heard = mutableListOf<String>()
        var flushed = mutableListOf<Boolean>()
        override fun speak(text: String, flush: Boolean, locale: Locale, rate: Float, pitch: Float): Boolean {
            if (!accepts) return false
            heard += text
            flushed += flush
            return true
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override val speaking: Boolean get() = false
    }

    private class Rig(
        val driver: FakeDriver = FakeDriver(),
        val attempts: MutableList<String> = mutableListOf(),
        val spoken: MutableList<String> = mutableListOf(),
        var error: Throwable = ApiError.Timeout(),
        var apiKey: String = "cle-test",
        var system: SystemTtsEngine? = SystemTtsEngine(driver),
    ) {
        val dir: File = Files.createTempDirectory("voixrepli").toFile()
        val clips = VoicePack(dir, packVoiceId = { "voix-test" }, bundledClip = { null })

        fun voice(): ProximityVoice = ProximityVoice(
            player = SpeechPlayer(File(dir, "play")),
            settings = { Settings.Default.copy(ttsApiKey = apiKey, ttsVoiceId = "voix-test") },
            onSpeaking = { spoken += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.failure(error)
            },
            clips = clips,
            system = system,
        )
    }

    private val networkDown: List<Throwable> = listOf(
        ApiError.InvalidKey(),
        ApiError.PaymentRequired(),
        ApiError.RateLimited(),
        ApiError.Timeout(),
        ApiError.Network(),
    )

    @Test
    fun `panne timeout, phrase dite au systeme en file`() = runTest {
        val rig = Rig()

        assertTrue(rig.voice().speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(listOf("Turn left in 20 meters."), rig.attempts)
        assertEquals(listOf("Turn left in 20 meters."), rig.driver.heard)
        assertEquals(listOf(false), rig.driver.flushed)
        assertEquals(listOf("Turn left in 20 meters."), rig.spoken)
    }

    @Test
    fun `cle credits cadence timeout reseau GPS, toutes repliees`() = runTest {
        networkDown.forEach { error ->
            val rig = Rig(error = error)

            assertTrue("$error", rig.voice().speak("Bordure détectée.", level = OutdoorLevel.GPS_EVENT))

            assertEquals(listOf("Bordure détectée."), rig.driver.heard)
        }
    }

    @Test
    fun `panne reseau sans moteur, phrase jetee`() = runTest {
        val rig = Rig(system = null)

        assertFalse(rig.voice().speak("Bordure détectée."))

        assertEquals(listOf("Bordure détectée."), rig.attempts)
        assertTrue(rig.driver.heard.isEmpty())
        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `erreur non reseau, jetee meme avec moteur`() = runTest {
        listOf<Throwable>(
            IllegalStateException("401"),
            ApiError.Http(500, "boom"),
            ApiError.BadResponse("vide"),
        ).forEach { error ->
            val rig = Rig(error = error)

            assertFalse("$error", rig.voice().speak("Bordure détectée."))

            assertTrue(rig.driver.heard.isEmpty())
            assertTrue(rig.spoken.isEmpty())
        }
    }

    @Test
    fun `mot panne reseau GPS, dit au systeme`() = runTest {
        val rig = Rig()

        assertTrue(rig.voice().speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))

        assertEquals(listOf("Gauche."), rig.driver.heard)
        assertEquals(listOf("Gauche."), rig.spoken)
    }

    @Test
    fun `sans cle, silence meme avec moteur`() = runTest {
        val rig = Rig(apiKey = "")

        assertFalse(rig.voice().speak("Bordure détectée."))

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.driver.heard.isEmpty())
        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `moteur qui refuse, phrase jetee`() = runTest {
        val rig = Rig(driver = FakeDriver(accepts = false))

        assertFalse(rig.voice().speak("Bordure détectée."))

        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `repli memorise, stop non repete`() = runTest {
        val rig = Rig()
        val voice = rig.voice()
        val stop = GuidanceWord.Stop.text(SpeechLanguage.Fr)

        assertTrue(voice.speakWord("guide_stop", stop, SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))
        assertFalse(voice.speakWord("guide_stop", stop, SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))

        assertEquals(listOf(stop), rig.driver.heard)
    }
}
