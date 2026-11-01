package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phrases **fixes** jouées en mp3 enregistré (SPEC v4) : « Car. » /
 * « Cars. » (et leurs FR) partent en clip — zéro synthèse. Hors GPS, une
 * phrase sans clip se tait (pas de synthèse) ; au niveau GPS, elle se
 * synthétise.
 */
class ProximityVoiceRecordedTest {

    private fun tempDir(): File = Files.createTempDirectory("fixes").toFile()

    private fun voice(
        clips: VoicePack?,
        attempts: MutableList<String>,
        spoken: MutableList<String>,
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = "cle-test", ttsVoiceId = "voix-test") },
        onSpeaking = { spoken += it },
        synthesize = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
        clips = clips,
    )

    private fun pack() = VoicePack(
        filesDir = tempDir(),
        packVoiceId = { "voix-test" },
        bundledClip = { "faux-mp3".toByteArray() },
    )

    @Test
    fun `mouvement de voiture joue le mp3, aucune synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(pack(), attempts, spoken).speak("Voiture.", level = OutdoorLevel.CAR_MOVING)

        assertTrue("parlé en clip", dit)
        assertTrue("aucune synthèse : le clip parle", attempts.isEmpty())
        assertEquals(listOf("Voiture."), spoken.filter { it.isNotBlank() })
    }

    @Test
    fun `phrase sans clip hors GPS = silence sans synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val v = voice(pack(), attempts, spoken)

        assertFalse(v.speak("Voiture à l'arrêt, à 2 mètres.", level = OutdoorLevel.HUMAN))
        v.reset()
        assertFalse(v.speak("2 vehicles moving.", level = OutdoorLevel.HUMAN))

        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `phrase sans clip niveau GPS = synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(pack(), attempts, spoken)
            .speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT)

        assertTrue(dit)
        assertEquals(listOf("Turn left in 20 meters."), attempts)
    }

    @Test
    fun `sans pack hors GPS = silence`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(clips = null, attempts, spoken).speak("Voiture.", level = OutdoorLevel.CAR_MOVING)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `table phrase vers clip, une seule source`() {
        assertEquals("car", recordedClipId("Voiture."))
        assertEquals("car", recordedClipId("Car."))
        assertEquals("cars", recordedClipId("Cars."))
        assertNull("le stop part en voix de sécurité", recordedClipId("Stop, car moving."))
        assertEquals("guide_left", spokenClipId("Gauche."))
        assertNull("phrase située : variable, pas de clip", recordedClipId("Car stopped, 2 meters away."))
    }
}
