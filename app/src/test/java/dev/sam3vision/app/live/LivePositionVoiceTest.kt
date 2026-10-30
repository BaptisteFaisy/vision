package dev.sam3vision.app.live

import dev.sam3vision.app.LiveUiState
import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.live.PositionAnnouncer
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.model.SegObject
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Annonce vocale des positions : une phrase par scène, publiée dans l'état puis lue, et
 * silence total pendant une question, sans clé TTS, ou sur une frame vide.
 */
class LivePositionVoiceTest {

    private val state = MutableStateFlow(LiveUiState())
    private val spoken = mutableListOf<String>()
    private val configured = Settings.Default.copy(
        ttsApiKey = "cle-tts",
        ttsVoiceId = "voix",
        speechLanguage = SpeechLanguage.Fr,
    )

    private fun voice(
        scope: TestScope,
        asking: Boolean = false,
        settings: Settings = configured,
    ) = LivePositionVoice(
        scope = scope,
        state = state,
        settings = { settings },
        asking = { asking },
        speak = { _, phrase -> spoken += phrase },
        announcer = PositionAnnouncer(minFrames = 1, cooldownMs = 0),
    )

    private val box = listOf(10f, 10f, 100f, 200f)

    private fun frame() = LiveFrame(
        width = 200,
        height = 200,
        elapsedMs = 10L,
        objects = listOf(SegObject("personne", 0.9f, box, "masque")),
    )

    @Test
    fun `la phrase est publiee dans l etat puis lue`() = runTest {
        val voice = voice(this)
        voice.announce(frame(), 200, 200, 1_000L, emptyList())
        val phrase = state.value.spoken
        assertNotNull("la phrase doit s'afficher en sous-titre", phrase)
        advanceUntilIdle()
        assertEquals(listOf(phrase), spoken)
    }

    @Test
    fun `silence pendant une question sur la memoire`() = runTest {
        val voice = voice(this, asking = true)
        voice.announce(frame(), 200, 200, 1_000L, emptyList())
        advanceUntilIdle()
        assertNull(state.value.spoken)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `silence sans cle tts`() = runTest {
        val voice = voice(this, settings = Settings.Default)
        voice.announce(frame(), 200, 200, 1_000L, emptyList())
        advanceUntilIdle()
        assertNull(state.value.spoken)
        assertTrue(spoken.isEmpty())
    }

    @Test
    fun `la distance suit la boite qui l a produite`() = runTest {
        val voice = voice(this)
        val reading = DistanceReading(trackId = 1, label = "personne", meters = 1.4f, rawMeters = 1.4f, box = box)
        voice.announce(frame(), 200, 200, 1_000L, listOf(reading))
        val phrase = state.value.spoken.orEmpty()
        assertTrue("la distance doit être dite : $phrase", phrase.contains("1 mètre"))
    }

    @Test
    fun `reset oublie la scene et la meme scene est redite`() = runTest {
        val voice = voice(this)
        voice.announce(frame(), 200, 200, 1_000L, emptyList())
        advanceUntilIdle()
        voice.announce(frame(), 200, 200, 1_100L, emptyList())
        advanceUntilIdle()
        assertEquals("même scène : pas de répétition", 1, spoken.size)
        voice.reset()
        voice.announce(frame(), 200, 200, 3_000L, emptyList())
        advanceUntilIdle()
        assertEquals(2, spoken.size)
    }

    @Test
    fun `un label maison est dit en francais`() = runTest {
        val voice = voice(this)
        val hunt = LiveFrame(
            width = 200,
            height = 200,
            elapsedMs = 10L,
            objects = listOf(SegObject("keys", 0.9f, box, "masque")),
        )
        voice.announce(hunt, 200, 200, 1_000L, emptyList())
        val phrase = state.value.spoken.orEmpty()
        assertTrue("maison dite en français : $phrase", phrase.startsWith("Clés "))
        advanceUntilIdle()
        assertEquals(listOf(phrase), spoken)
    }

    @Test
    fun `voiture a 7 m dite et personne a 6 m tue`() = runTest {
        val voice = voice(this)
        val carBox = listOf(10f, 10f, 100f, 200f)
        val personBox = listOf(110f, 10f, 200f, 200f)
        val street = LiveFrame(
            width = 200,
            height = 200,
            elapsedMs = 10L,
            objects = listOf(
                SegObject("car", 0.9f, carBox, "masque"),
                SegObject("personne", 0.9f, personBox, "masque"),
            ),
        )
        voice.announce(
            street, 200, 200, 1_000L,
            listOf(
                DistanceReading(trackId = 1, label = "car", meters = 7f, rawMeters = 7f, box = carBox),
                DistanceReading(trackId = 2, label = "personne", meters = 6f, rawMeters = 6f, box = personBox),
            ),
        )

        assertEquals("Car à 7 mètres.", state.value.spoken)
    }

    @Test
    fun `un label de rue est dit tel quel`() = runTest {
        val voice = voice(this)
        voice.announce(frame(), 200, 200, 1_000L, emptyList())
        val phrase = state.value.spoken.orEmpty()
        assertTrue("rue inchangée : $phrase", phrase.startsWith("Personne "))
    }
}