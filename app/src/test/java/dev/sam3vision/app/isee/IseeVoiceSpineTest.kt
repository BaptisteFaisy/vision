package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.IseeClipsEn
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
 * Trouvé de la chasse, clips uniquement : une fois par couple (objet,
 * cadran), atome de l'objet sinon Found puis cadran (anglais, toute langue
 * de voix) ; sans câblage séquence, chaque clip part en texte.
 */
class IseeVoiceSpineTest {

    private val spoken = mutableListOf<String>()
    private val clipSeqs = mutableListOf<List<String>>()
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
        speakClips = { clipSeqs += it },
    )

    private fun spineUnwired(scope: CoroutineScope) = IseeVoiceSpine(
        scope = scope,
        crossingActive = { false },
        placeMode = { PictoPlaceMode.DEDANS },
        cutSpeech = {},
        stopVoice = {},
        speakPending = {},
        speak = { spoken += it },
        settings = { Settings.Default },
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
    fun `trouve connu parle objet puis cadran, une fois par couple`() = runTest {
        val voice = spine(this)
        voice.speakFound("keys", 9)
        voice.speakFound("keys", 9)
        voice.speakFound("keys", 3)
        assertEquals(
            listOf(
                listOf(IseeClipsEn.KEYS, IseeClipsEn.CLOCK_9),
                listOf(IseeClipsEn.KEYS, IseeClipsEn.CLOCK_3),
            ),
            clipSeqs,
        )
    }

    @Test
    fun `trouve inconnu parle found puis cadran`() = runTest {
        spine(this).speakFound("mug", 9)
        assertEquals(listOf(listOf(IseeClipsEn.FOUND, IseeClipsEn.CLOCK_9)), clipSeqs)
    }

    @Test
    fun `trouve parle les clips anglais quelle que soit la langue`() = runTest {
        spine(this, SpeechLanguage.Fr).speakFound("socks", 12)
        assertEquals(listOf(listOf(IseeClipsEn.SOCKS, IseeClipsEn.CLOCK_12)), clipSeqs)
    }

    @Test
    fun `trouve sans cablage parle chaque clip en texte`() = runTest {
        spineUnwired(this).speakFound("phone", 5)
        assertEquals(listOf(IseeClipsEn.PHONE_TEXT, IseeClipsEn.CLOCK_5_TEXT), spoken)
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
