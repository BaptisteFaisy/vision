package dev.sam3vision.app.isee

import dev.sam3vision.app.SpeechPlayer
import dev.sam3vision.app.speech.VoicePack
import dev.sam3vision.core.live.GuidanceWord
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
 * Voix de proximité SPEC v4 : parle ou se tait, sans file — clips
 * d'abord, synthèse GPS sinon (hors GPS sans clip : silence), coupe du
 * lecteur partagé, parole du user devant tout sauf le niveau 0, silence
 * sans clé (repli supprimé).
 */
class ProximityVoiceEchelleTest {

    private class Rig(
        val attempts: MutableList<String> = mutableListOf(),
        val spoken: MutableList<String> = mutableListOf(),
        var canPlay: Boolean = true,
        var systemBusy: Boolean = false,
        var userSpeaking: Boolean = false,
        var fails: (String) -> Result<ByteArray> = { text -> Result.success("$text-audio".toByteArray()) },
    ) {
        val dir: File = Files.createTempDirectory("voixechelle").toFile()
        val clips = VoicePack(dir, packVoiceId = { "voix-test" }, bundledClip = { null })

        fun voice(key: String = "cle-test"): ProximityVoice = ProximityVoice(
            player = SpeechPlayer(File(dir, "play")),
            settings = { Settings.Default.copy(ttsApiKey = key, speechLanguage = SpeechLanguage.Fr) },
            onSpeaking = { spoken += it },
            canPlay = { canPlay },
            synthesize = { _, _, text ->
                attempts += text
                fails(text)
            },
            clips = clips,
            systemBusy = { systemBusy },
            userSpeaking = { userSpeaking },
        )

        /** Dits non vides (sous-titres) : les coupes (`""`) sont comptées à part. */
        fun said(): List<String> = spoken.filter { it.isNotEmpty() }

        fun cuts(): Int = spoken.count { it.isEmpty() }
    }

    @Test
    fun `phrase GPS parlee en synthese, sans file ni attente`() = runTest {
        val rig = Rig()
        val voice = rig.voice()

        assertTrue(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))
        assertTrue(voice.speak("Continue straight ahead.", level = OutdoorLevel.GPS_REMINDER))

        assertEquals(listOf("Turn left in 20 meters.", "Continue straight ahead."), rig.attempts)
        assertEquals(listOf("Turn left in 20 meters.", "Continue straight ahead."), rig.said())
    }

    @Test
    fun `phrase hors GPS sans clip = silence sans appel reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice()

        assertFalse(voice.speak("Bordure détectée."))
        assertFalse(voice.speak("Trottoir à gauche."))

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.said().isEmpty())
    }

    @Test
    fun `phrase fixe en clip, zero reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice()
        rig.clips.put(
            "car", SpeechLanguage.Fr,
            Settings.Default.ttsVoiceId, "mp3".toByteArray(),
        )

        assertTrue(voice.speak("Voiture.", level = OutdoorLevel.CAR_MOVING))

        assertTrue(rig.attempts.isEmpty())
        assertEquals(listOf("Voiture."), rig.said())
    }

    @Test
    fun `sans cle, silence sans appel reseau`() = runTest {
        val rig = Rig()
        val voice = rig.voice(key = "")

        assertFalse(voice.speak("Bordure détectée."))

        assertTrue(rig.attempts.isEmpty())
        assertTrue(rig.said().isEmpty())
    }

    @Test
    fun `synthese en echec, phrase jetee sans file`() = runTest {
        val rig = Rig(fails = { Result.failure(IllegalStateException("401")) })
        val voice = rig.voice()

        assertFalse(voice.speak("Turn left in 20 meters.", level = OutdoorLevel.GPS_EVENT))

        assertEquals(listOf("Turn left in 20 meters."), rig.attempts)
        assertTrue(rig.said().isEmpty())
    }

    @Test
    fun `parole du user coupe une fois et tait, re-armee au reset`() = runTest {
        val rig = Rig(userSpeaking = true)
        val voice = rig.voice()

        assertFalse(voice.speak("Bordure détectée."))
        assertFalse(voice.speak("Trottoir à gauche."))
        assertEquals(1, rig.cuts())
        assertTrue(rig.attempts.isEmpty())

        voice.reset()
        assertFalse(voice.speak("Bordure détectée."))
        assertEquals(2, rig.cuts())
    }

    @Test
    fun `niveau 0 seul coupe la parole du user`() = runTest {
        val rig = Rig(userSpeaking = true)
        val voice = rig.voice()
        rig.clips.put("car", SpeechLanguage.Fr, Settings.Default.ttsVoiceId, "mp3".toByteArray())

        assertTrue(voice.speak("Voiture.", level = OutdoorLevel.CAR_MOVING))

        assertTrue(rig.attempts.isEmpty())
        assertEquals(listOf("Voiture."), rig.said())
    }

    @Test
    fun `porte fermee tait sauf niveau 0`() = runTest {
        val rig = Rig(canPlay = false)
        val voice = rig.voice()

        assertFalse(voice.speak("Bordure détectée."))
        rig.clips.put("car", SpeechLanguage.Fr, Settings.Default.ttsVoiceId, "mp3".toByteArray())
        assertTrue(voice.speak("Voiture.", level = OutdoorLevel.CAR_MOVING))

        assertTrue(rig.attempts.isEmpty())
    }

    @Test
    fun `tts systeme occupe tait, jamais de chevauchement`() = runTest {
        val rig = Rig(systemBusy = true)
        val voice = rig.voice()

        assertFalse(voice.speak("Voiture.", level = OutdoorLevel.CAR_MOVING))
        assertTrue(rig.attempts.isEmpty())
    }

    @Test
    fun `mot en clip sans synthese, sinon synthese mise en cache`() = runTest {
        val rig = Rig()
        val voice = rig.voice()
        rig.clips.put("guide_left", SpeechLanguage.Fr, Settings.Default.ttsVoiceId, "mp3".toByteArray())

        assertTrue(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr))
        assertTrue(rig.attempts.isEmpty())

        assertTrue(voice.speakWord("guide_right", "Droite.", SpeechLanguage.Fr, level = OutdoorLevel.GPS_EVENT))
        assertEquals(listOf("Droite."), rig.attempts)
        assertTrue(
            rig.clips.clipFor("guide_right", SpeechLanguage.Fr, Settings.Default.ttsVoiceId)?.isFile == true,
        )
        assertEquals(listOf("Gauche.", "Droite."), rig.said())
    }

    @Test
    fun `stop jamais repete, re-arme au reset`() = runTest {
        val rig = Rig()
        val voice = rig.voice()
        val stop = GuidanceWord.Stop.text(SpeechLanguage.Fr)
        rig.clips.put("guide_stop", SpeechLanguage.Fr, Settings.Default.ttsVoiceId, "mp3".toByteArray())

        assertTrue(voice.speakWord("guide_stop", stop, SpeechLanguage.Fr))
        assertFalse(voice.speakWord("guide_stop", stop, SpeechLanguage.Fr))
        assertTrue(rig.attempts.isEmpty())

        voice.reset()
        assertTrue(voice.speakWord("guide_stop", stop, SpeechLanguage.Fr))
        assertTrue(rig.attempts.isEmpty())
        assertEquals(listOf(stop, stop), rig.said())
    }

    @Test
    fun `mot cede au user sauf niveau 0`() = runTest {
        val rig = Rig(userSpeaking = true)
        val voice = rig.voice()

        rig.clips.put("guide_left", SpeechLanguage.Fr, Settings.Default.ttsVoiceId, "mp3".toByteArray())
        assertFalse(voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr))
        assertTrue(
            voice.speakWord("guide_left", "Gauche.", SpeechLanguage.Fr, level = OutdoorLevel.CAR_MOVING),
        )
        assertTrue(rig.attempts.isEmpty())
    }

    @Test
    fun `blanc jamais parle`() = runTest {
        val rig = Rig()
        val voice = rig.voice()

        assertFalse(voice.speak("  "))
        assertFalse(voice.speakWord("guide_left", "", SpeechLanguage.Fr))
        assertTrue(rig.attempts.isEmpty())
    }
}
