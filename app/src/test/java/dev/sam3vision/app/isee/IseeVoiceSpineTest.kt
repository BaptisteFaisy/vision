package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Trouvé de la chasse : parlé une fois par couple (objet, cadran), dans la
 * langue de la voix.
 */
class IseeVoiceSpineTest {

    private val spoken = mutableListOf<String>()
    private val posted = mutableListOf<Pair<Int, String>>()

    private fun spine(scope: CoroutineScope, language: SpeechLanguage = SpeechLanguage.En) = IseeVoiceSpine(
        scope = scope,
        crossingActive = { false },
        placeMode = { PictoPlaceMode.DEDANS },
        cutSpeech = {},
        stopVoice = {},
        speakPending = {},
        speak = { spoken += it },
        settings = { Settings.Default.copy(speechLanguage = language) },
    )

    private fun spineDehors(scope: CoroutineScope, onResume: () -> Unit = {}) = IseeVoiceSpine(
        scope = scope,
        crossingActive = { false },
        placeMode = { PictoPlaceMode.DEHORS },
        cutSpeech = {},
        stopVoice = {},
        speakPending = { onResume() },
        speak = { spoken += it },
        settings = { Settings.Default },
    )

    @Before
    fun brancher() {
        VoiceBus.reset()
        VoiceBus.speaker = object : VoiceBusSpeaker {
            override fun idle(): Boolean = true
            override fun say(level: Int, text: String, clipId: String?, clips: List<String>) {
                posted += level to text
            }

            override fun cut() = Unit
        }
    }

    @After
    fun debrancher() {
        VoiceBus.speaker = null
        VoiceBus.reset()
        posted.clear()
    }

    @Test
    fun `trouve parle une fois par objet et cadran`() = runTest {
        val voice = spine(this)
        voice.speakFound("tasse-spine", 9)
        voice.speakFound("tasse-spine", 9)
        voice.speakFound("tasse-spine", 3)
        assertEquals(
            listOf("Tasse-spine at 9 o'clock.", "Tasse-spine at 3 o'clock."),
            spoken,
        )
    }

    @Test
    fun `trouve parle la langue de la voix`() = runTest {
        spine(this, SpeechLanguage.Fr).speakFound("mug-spine", 12)
        assertEquals(listOf("Mug-spine à midi."), spoken)
    }

    @Test
    fun `reprise chat attendue sous des escaliers`() = runTest {
        var resumed = false
        val voice = spineDehors(this) { resumed = true }
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")

        voice.resumeChatIfDue()
        assertFalse(resumed)

        VoiceBus.reset()
        voice.resumeChatIfDue()
        assertTrue(resumed)
    }

    @Test
    fun `gps reporte au silence sous un compte`() = runTest {
        val voice = spineDehors(this)
        VoiceBus.post(OutdoorLevel.STAIRS, "5 marches.")

        voice.speakGps("Tournez à droite.", OutdoorLevel.GPS_EVENT)
        runCurrent()
        assertEquals(listOf(OutdoorLevel.STAIRS to "5 marches."), posted)

        VoiceBus.onSpeechIdle()
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(
            listOf(
                OutdoorLevel.STAIRS to "5 marches.",
                OutdoorLevel.GPS_EVENT to "Tournez à droite.",
            ),
            posted,
        )
    }
}
