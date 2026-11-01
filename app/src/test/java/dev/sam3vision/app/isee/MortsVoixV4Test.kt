package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.net.ApiError
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPEC v4 morts-voix : la porte GPS est supprimée ([canGuideSpeech] ne cède
 * qu'au chat et au user), le repli hors-ligne et son signal sont supprimés
 * (sans clé ou voix réseau en panne : silence, jamais de TTS système — seuls
 * les clips parlent sans réseau).
 */
class MortsVoixV4Test {

    @Test
    fun `porte guidage cede au chat et au user, sans porte GPS`() {
        assertTrue(canGuideSpeech(playerIdle = true, userHolding = false))
        assertFalse(canGuideSpeech(playerIdle = false, userHolding = false))
        assertFalse(canGuideSpeech(playerIdle = true, userHolding = true))
        assertFalse(canGuideSpeech(playerIdle = false, userHolding = true))
    }

    @Test
    fun `sans cle, doux silencieux sans appel reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "")

        voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())

        // En file puis au silence : toujours silencieux, toujours sans appel.
        voice.onPhrase("Personne à 1 mètre.", playerIdle = false, queue = true)
        voice.flushPending(playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `401 402 429 silencieux, sans repli ni signal`() = runTest {
        val errors = listOf(
            ApiError.InvalidKey("401"),
            ApiError.PaymentRequired(),
            ApiError.RateLimited(),
        )
        for (error in errors) {
            val rig = Rig()
            rig.fails = { Result.failure(error) }
            val voice = rig.voice()

            voice.onPhrase("Personne à 1 mètre.", playerIdle = true)

            assertEquals(listOf("Personne à 1 mètre."), rig.attempts)
            assertTrue(rig.spoken.isEmpty())
        }
    }

    @Test
    fun `speakWord sans cle silencieux sans appel reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "")

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.spoken.isEmpty())
    }

    @Test
    fun `clips parles sans cle ni reseau`() = runTest {
        val rig = Rig()
        val pack = VoicePack(
            filesDir = rig.tempDir(),
            packVoiceId = { Settings.Default.ttsVoiceId },
            bundledClip = { "faux-mp3".toByteArray() },
        )
        val voice = rig.voice(key = "", clips = pack)

        voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, playerIdle = true)

        assertTrue(rig.attempts.isEmpty())
        assertEquals(listOf("Gauche."), rig.spoken)
    }

    /** Banc minimal, miroir de `ProximityVoiceTest.Rig` sans le moteur système (repli supprimé). */
    private class Rig(
        val attempts: MutableList<String> = mutableListOf(),
        val spoken: MutableList<String> = mutableListOf(),
        var fails: (String) -> Result<ByteArray> = { text -> Result.success("$text-audio".toByteArray()) },
    ) {
        fun tempDir(): File = Files.createTempDirectory("mortvoix").toFile()

        fun voice(key: String = "cle-test", clips: VoicePack? = null): ProximityVoice =
            ProximityVoice(
                player = SpeechPlayer(File(tempDir(), "play")),
                settings = { Settings.Default.copy(ttsApiKey = key) },
                onSpeaking = { if (it.isNotEmpty()) spoken += it },
                synthesize = { _, _, text ->
                    attempts += text
                    fails(text)
                },
                clips = clips,
            )
    }
}
