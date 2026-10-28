package dev.sam3vision.app.isee.vm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Les motifs affichés par l'écran iSee : les phrases sont écrites en un seul
 * endroit. Ces tests fixent le texte exact — la découpe du ViewModel ne doit
 * rien changer de ce que lit l'utilisateur.
 */
class IseeStatusTextTest {

    @Test
    fun `motifs d'erreur — le message du moteur, sinon le repli`() {
        assertEquals("Dictation: réseau coupé", IseeStatusText.dictationFailed(Exception("réseau coupé")))
        assertEquals("Dictation: failed", IseeStatusText.dictationFailed(Exception()))
        assertEquals("Voice: unavailable", IseeStatusText.voiceFailed(Exception()))
        assertEquals("Question failed: error", IseeStatusText.questionFailed(Exception()))
    }

    @Test
    fun `etapes — textes affiches pendant une tenue`() {
        assertEquals("Transcribing", IseeStatusText.STEP_TRANSCRIBING)
        assertEquals("Asking", IseeStatusText.STEP_ASKING)
        assertEquals("Speaking", IseeStatusText.STEP_SPEAKING)
        assertEquals("Asking timed out — try again", IseeStatusText.timedOut(IseeStatusText.STEP_ASKING))
    }

    @Test
    fun `timeout d'etape — chaque motif nomme l'etape au lieu du prefixe`() {
        assertEquals(
            "Transcribing timed out — try again",
            IseeStatusText.dictationFailed(StepTimeout(IseeStatusText.STEP_TRANSCRIBING)),
        )
        assertEquals(
            "Speaking timed out — try again",
            IseeStatusText.voiceFailed(StepTimeout(IseeStatusText.STEP_SPEAKING)),
        )
        assertEquals(
            "Asking timed out — try again",
            IseeStatusText.questionFailed(StepTimeout(IseeStatusText.STEP_ASKING)),
        )
    }

    @Test
    fun `motifs deja vus sur le telephone — identiques apres decoupe`() {
        assertEquals("Microphone unavailable", IseeStatusText.MIC_UNAVAILABLE)
        assertEquals("Camera not ready — try again", IseeStatusText.CAMERA_NOT_READY)
        assertEquals("Busy speaking — try again", IseeStatusText.BUSY_SPEAKING)
        assertEquals("Nothing heard", IseeStatusText.NOTHING_HEARD)
        assertEquals("Answer still coming — ask again after it", IseeStatusText.ANSWER_PENDING)
        assertEquals("No LLM key configured (Settings)", IseeStatusText.NO_LLM_KEY)
        assertEquals("Recording lost", IseeStatusText.RECORDING_LOST)
        assertEquals(
            "No dictation engine configured (Settings)",
            IseeStatusText.NO_DICTATION_ENGINE,
        )
    }
}