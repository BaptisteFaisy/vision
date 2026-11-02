package dev.sam3vision.app.isee

import dev.sam3vision.app.PlaybackEnd
import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIX 5 : un échec synchrone du lecteur (fichier illisible, préparation
 * refusée — en JVM : MediaPlayer indisponible) déclenche le rappel
 * d'erreur, qui rend le bus vocal. Sans ça, `ProximityVoice.speak`
 * rendait `true`, aucun rappel ne partait jamais, et `VoiceBus` restait
 * coincé au niveau parlé : tout niveau plus bas était jeté pour
 * toujours — silence outdoor total jusqu'au redémarrage (02/11).
 */
class Fix5SyncFailureReleasesBusTest {

    /** Puits clips du bus : jamais appelé ici (l'id n'existe pas dans IseeClipsEn). */
    private object UnusedSink : ClipSink {
        override suspend fun playFile(file: File): Result<Unit> = error("inattendu")
        override suspend fun playSlice(file: File, sliceMs: Long): Result<Unit> = error("inattendu")
        override suspend fun playQueued(bytes: ByteArray): Result<PlaybackEnd> = error("inattendu")
        override fun stop() = Unit
    }

    private fun tempDir(): File = Files.createTempDirectory("fix5").toFile()

    private fun pack(voix: String = "voix-test"): VoicePack = VoicePack(
        filesDir = tempDir(),
        packVoiceId = { voix },
        bundledClip = { "faux-mp3".toByteArray() },
    )

    @Before
    fun brancher() {
        VoiceBus.reset()
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
    }

    @Test
    fun `echec synchrone = rappel erreur`() = runTest {
        var erreur: String? = null
        val lecteur = SpeechPlayer(tempDir(), onPlaybackError = { erreur = it })

        val resultat = lecteur.playSliceFile(File(tempDir(), "x.mp3"))

        assertTrue(resultat.isFailure)
        assertNotNull(erreur)
    }

    @Test
    fun `echec synchrone = bus rendu`() = runTest {
        VoiceBus.speaker = object : VoiceBusSpeaker {
            override fun idle(): Boolean = true
            override fun say(level: Int, text: String, clipId: String?, clips: List<String>) = Unit
            override fun cut() = Unit
        }
        val proximity = ProximityVoice(
            player = SpeechPlayer(
                File(tempDir(), "play"),
                // Même câblage que le ViewModel : fin (erreur comprise) = bus rendu.
                onPlaybackError = { VoiceBus.onSpeechIdle() },
            ),
            settings = { Settings.Default.copy(ttsVoiceId = "voix-test") },
            clips = pack(),
        )
        // "Voiture." a un clip pack (RecordedPhrase) : la proximité tente la
        // lecture, qui échoue en synchrone (MediaPlayer indisponible en JVM).
        VoiceBus.post(OutdoorLevel.STAIRS, "prise")
        assertTrue(VoiceBus.stairsSpeaking())

        proximity.speak("Voiture.", level = OutdoorLevel.STAIRS)

        assertFalse(VoiceBus.stairsSpeaking())
    }

    @Test
    fun `echec synchrone via post = escaliers ne restent pas cries`() = runTest {
        val settings = { Settings.Default.copy(ttsVoiceId = "voix-test") }
        val proximity = ProximityVoice(
            player = SpeechPlayer(
                File(tempDir(), "play"),
                onPlaybackError = { VoiceBus.onSpeechIdle() },
            ),
            settings = settings,
            clips = pack(),
        )
        val busClips = IseeClipPlayer(pack(), { "voix-test" }, UnusedSink)
        VoiceBus.speaker = BusSpeaker(this, SpeechPlayer(File(tempDir(), "bus")), busClips, proximity)

        VoiceBus.post(OutdoorLevel.STAIRS, "Voiture.", clipId = "clip-inexistant-xyz")
        advanceUntilIdle()
        // La lecture échoue sur un thread IO réel : l'attente est en temps
        // réel (un délai virtuel instantané devancerait le thread IO).
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                while (VoiceBus.stairsSpeaking()) delay(25)
            }
        }

        assertFalse(VoiceBus.stairsSpeaking())
    }
}
