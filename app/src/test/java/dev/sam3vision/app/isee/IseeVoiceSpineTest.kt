package dev.sam3vision.app.isee

import dev.sam3vision.core.picto.PictoPlaceMode
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Trouvé de la chasse : parlé une fois par couple (objet, cadran), dans la
 * langue de la voix.
 */
class IseeVoiceSpineTest {

    private val spoken = mutableListOf<String>()

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
}
