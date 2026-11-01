package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phrases **fixes** d'état jouées en mp3 enregistré (ordre user du 01/11 :
 * « que cela soit un mp3 enregistré ») : le mouvement d'un véhicule ou d'un
 * cycle ne porte plus de position, donc le pack le dit — zéro synthèse. Ce qui
 * porte encore une mesure (arrêt, stationnement) ou un compte (pluriel) reste
 * en synthèse.
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

        voice(pack(), attempts, spoken).onPhrase("Voiture en mouvement.", playerIdle = true)

        assertTrue("aucune synthèse : le clip parle", attempts.isEmpty())
        assertEquals(listOf("Voiture en mouvement."), spoken.filter { it.isNotBlank() })
    }

    @Test
    fun `mouvement de cycle joue son mp3`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        voice(pack(), attempts, spoken).onPhrase("Attention, vélo en mouvement.", playerIdle = true)

        assertTrue(attempts.isEmpty())
        assertEquals(listOf("Attention, vélo en mouvement."), spoken.filter { it.isNotBlank() })
    }

    @Test
    fun `arret situe et pluriel restent en synthese`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val v = voice(pack(), attempts, spoken)

        v.onPhrase("Voiture à l'arrêt, à 2 mètres.", playerIdle = true)
        v.reset()
        v.onPhrase("2 vehicles moving.", playerIdle = true)

        assertEquals(listOf("Voiture à l'arrêt, à 2 mètres.", "2 vehicles moving."), attempts)
    }

    @Test
    fun `sans pack, la phrase fixe se synthetise encore`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        voice(clips = null, attempts, spoken).onPhrase("Voiture en mouvement.", playerIdle = true)

        assertEquals(listOf("Voiture en mouvement."), attempts)
    }

    @Test
    fun `table phrase vers clip, une seule source`() {
        assertEquals("vehicle_car_moving", recordedClipId("Voiture en mouvement."))
        assertNull("le stop part en voix de sécurité", recordedClipId("Stop, car moving."))
        assertEquals("cycle_velo_moving", recordedClipId("Attention, vélo en mouvement."))
        assertEquals("vehicle_car_moving", spokenClipId("Car moving."))
        assertEquals("guide_left", spokenClipId("Gauche."))
        assertNull("phrase située : variable, pas de clip", recordedClipId("Car stopped, 2 meters away."))
    }
}
