package dev.sam3vision.app.isee

import dev.sam3vision.core.iseedesign.IseeScene
import dev.sam3vision.core.iseedesign.SpeechPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IseeCaptionStateTest {
    private val playing = SpeechPosition(500L, 2_000L)
    private val state = IseeUiState(
        entries = listOf(ChatEntry.AiBubble(42, "a chair near you", null)),
        selected = 0,
    )

    @Test
    fun `silence preparation fin interruption et reflexion ne laissent aucun texte`() {
        assertNull(currentCaption(state, null))
        assertNull(currentCaption(state.copy(speakingEntryId = 42), null))
        assertNull(currentCaption(state.copy(thinking = true), playing))
        assertNull(currentCaption(state, playing))
    }

    @Test
    fun `le texte suit la voix jouee plutot que la bulle selectionnee`() {
        assertEquals("a chair near you", currentCaption(state.copy(speakingEntryId = 42), playing)?.text)
        val announcement = state.copy(speakingEntryId = ANNOUNCEMENT_BUBBLE_ID, speakingText = "Turn left")
        assertEquals("Turn left", currentCaption(announcement, playing)?.text)
        assertEquals("Turn left", currentCaption(announcement.copy(holding = true), playing)?.text)
        assertEquals(IseeScene.VOICE, iseeSceneOf(announcement.copy(recordingVideo = true), null))
    }

    @Test
    fun `le texte user disparait au silence et au relachement`() {
        val listening = state.copy(holding = true, userSpeaking = true, liveTranscript = "where is the door")
        assertEquals("where is the door", currentCaption(listening, null)?.text)
        assertNull(currentCaption(listening.copy(userSpeaking = false), null))
        assertNull(currentCaption(listening.copy(holding = false, transcribing = true), null))
    }
}
