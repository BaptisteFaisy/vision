package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.settings.Settings
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parole de proximité (SPEC v4) : clip d'abord (id fourni ou retrouvé),
 * synthèse GPS sinon, silence hors GPS sans clip — aucune file, aucun
 * réessai. La parole du user passe devant tout sauf le niveau 0 (coupe
 * unique, ré-armée) ; sans clé, le GPS se tait mais les clips parlent.
 */
class ProximityVoiceSpeakTest {

    private fun tempDir(): File = Files.createTempDirectory("parole").toFile()

    private fun pack(voix: String = "voix-test"): VoicePack = VoicePack(
        filesDir = tempDir(),
        packVoiceId = { voix },
        bundledClip = { "faux-mp3".toByteArray() },
    )

    private fun voice(
        attempts: MutableList<String>,
        spoken: MutableList<String>,
        clips: VoicePack? = pack(),
        apiKey: String = "cle-test",
        canPlay: () -> Boolean = { true },
        systemBusy: () -> Boolean = { false },
        userSpeaking: () -> Boolean = { false },
        synthesize: suspend (String, String, String) -> Result<ByteArray> = { _, _, text ->
            attempts += text
            Result.success("$text-audio".toByteArray())
        },
    ) = ProximityVoice(
        player = SpeechPlayer(File(tempDir(), "play")),
        settings = { Settings.Default.copy(ttsApiKey = apiKey, ttsVoiceId = "voix-test") },
        onSpeaking = { spoken += it },
        canPlay = canPlay,
        synthesize = synthesize,
        clips = clips,
        systemBusy = systemBusy,
        userSpeaking = userSpeaking,
    )

    /** Phrases réellement dites (le fanion rendu n'est pas une phrase). */
    private fun said(spoken: List<String>): List<String> = spoken.filter { it.isNotBlank() }

    @Test
    fun `clips parles sans cle`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = ProximityVoice(
            player = SpeechPlayer(File(tempDir(), "play")),
            settings = { Settings.Default.copy(ttsApiKey = "") },
            onSpeaking = { if (it.isNotEmpty()) spoken += it },
            synthesize = { _, _, text ->
                attempts += text
                Result.success("$text-audio".toByteArray())
            },
            clips = VoicePack(
                filesDir = tempDir(),
                packVoiceId = { Settings.Default.ttsVoiceId },
                bundledClip = { "faux-mp3".toByteArray() },
            ),
        )

        assertTrue(voice.speak("Gauche.", clipId = "guide_left"))

        assertTrue(attempts.isEmpty())
        assertEquals(listOf("Gauche."), spoken)
    }

    @Test
    fun `sans cle GPS = silence sans appel reseau`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken, apiKey = "")
            .speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `user muet, annonce parlee`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken)
            .speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT)

        assertTrue(dit)
        assertEquals(listOf("Turn left in 20 meters."), said(spoken))
    }

    @Test
    fun `user qui parle, annonce jetee et coupe une fois`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        val voice = voice(attempts, spoken, userSpeaking = { true })

        assertFalse(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))
        assertFalse(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertTrue(attempts.isEmpty())
        assertEquals(listOf(""), spoken)
    }

    @Test
    fun `niveau 0 parle par-dessus le user`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken, userSpeaking = { true })
            .speak("Voiture.", level = OutdoorLevel.CAR_MOVING)

        assertTrue(dit)
        assertEquals(listOf("Voiture."), said(spoken))
    }

    @Test
    fun `userIsSpeaking reflete le micro`() {
        var speaking = false
        val voice = voice(mutableListOf(), mutableListOf(), userSpeaking = { speaking })

        assertFalse(voice.userIsSpeaking())
        speaking = true
        assertTrue(voice.userIsSpeaking())
    }

    @Test
    fun `blanc = jete sans rien toucher`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        assertFalse(voice(attempts, spoken).speak("  ", level = OutdoorLevel.GPS_EVENT))

        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `porte fermee hors 0 = jete`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken, canPlay = { false })
            .speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT)

        assertFalse(dit)
        assertTrue(attempts.isEmpty())
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `parole dite re-arme la coupe user`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()
        var speaking = true
        val voice = voice(attempts, spoken, userSpeaking = { speaking })

        assertFalse(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))
        speaking = false
        assertTrue(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))
        speaking = true
        assertFalse(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(1, said(spoken).size)
        assertEquals("", spoken.first())
        assertEquals("", spoken.last())
    }

    @Test
    fun `echec synthese = jete`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken, synthesize = { _, _, text ->
            attempts += text
            Result.failure(RuntimeException("reseau"))
        }).speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT)

        assertFalse(dit)
        assertEquals(listOf("Turn left in 20 meters."), attempts)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `clipId direct prime, texte sous-titre`() = runTest {
        val attempts = mutableListOf<String>()
        val spoken = mutableListOf<String>()

        val dit = voice(attempts, spoken).speak("n'importe quoi", clipId = "guide_left")

        assertTrue(dit)
        assertTrue(attempts.isEmpty())
        assertEquals("n'importe quoi", spoken.first())
    }
}
