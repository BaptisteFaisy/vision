package dev.sam3vision.app.isee

import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.app.voicenav.sharedTripOf
import dev.sam3vision.core.nav.GuidanceUpdate
import dev.sam3vision.core.nav.RouteSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IseeShareTest {
    @Test
    fun `the caregiver gets the active camera obstacles`() {
        val scene = sharedSceneOf(IseeUiState(recordingVideo = true), LiveProbeState(spoken = "Door on the right."))
        assertEquals("Door on the right.", scene?.text)
    }

    @Test
    fun `an old instruction never survives the camera closing`() {
        assertNull(sharedSceneOf(IseeUiState(), LiveProbeState(spoken = "Turn right.")))
    }

    @Test
    fun `the guided trip yields the loved one bar, from start to destination`() {
        val guiding = VoiceNavUiState.Guiding(
            destination = "Gare de Lyon",
            summary = RouteSummary("Gare de Lyon", null, 1_200, 900),
            update = GuidanceUpdate("Turn right", 40, 300, 240),
        )
        val trip = sharedTripOf(guiding)
        assertEquals("Gare de Lyon", trip?.destination)
        assertEquals(1_200L, trip?.totalMeters)
        assertEquals(300L, trip?.remainingMeters)
        assertEquals(240L, trip?.remainingSeconds)
        assertEquals(0.75f, trip?.progress ?: -1f, 0.001f)
    }

    @Test
    fun `outside guidance no bar survives the previous trip`() {
        assertNull(sharedTripOf(VoiceNavUiState.Idle))
        assertNull(sharedTripOf(VoiceNavUiState.Confirming("Gare de Lyon", null, false, null)))
    }

    @Test
    fun `the last message and its read state share with no history`() {
        val entry = ChatEntry.AiBubble(42, "An obstacle ahead of you.", null)
        val state = IseeUiState(entries = listOf(entry), selected = 1, speakingEntryId = 42)
        val scene = sharedSceneOf(state, LiveProbeState())
        assertEquals(entry.text, scene?.text)
        assertEquals(true, scene?.speaking)
        assertEquals(false, scene?.fromUser)
    }
}
