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
    fun `le caregiver recoit les obstacles de la camera active`() {
        val scene = sharedSceneOf(IseeUiState(recordingVideo = true), LiveProbeState(spoken = "Porte à droite."))
        assertEquals("Porte à droite.", scene?.text)
    }

    @Test
    fun `une ancienne consigne ne survit pas a la fermeture de la camera`() {
        assertNull(sharedSceneOf(IseeUiState(), LiveProbeState(spoken = "Tourne à droite.")))
    }

    @Test
    fun `le trajet guide donne la barre du proche, du depart a l'arrivee`() {
        val guiding = VoiceNavUiState.Guiding(
            destination = "Gare de Lyon",
            summary = RouteSummary("Gare de Lyon", null, 1_200, 900),
            update = GuidanceUpdate("Tournez à droite", 40, 300, 240),
        )
        val trip = sharedTripOf(guiding)
        assertEquals("Gare de Lyon", trip?.destination)
        assertEquals(1_200L, trip?.totalMeters)
        assertEquals(300L, trip?.remainingMeters)
        assertEquals(240L, trip?.remainingSeconds)
        assertEquals(0.75f, trip?.progress ?: -1f, 0.001f)
    }

    @Test
    fun `hors guidage aucune barre ne survit au trajet precedent`() {
        assertNull(sharedTripOf(VoiceNavUiState.Idle))
        assertNull(sharedTripOf(VoiceNavUiState.Confirming("Gare de Lyon", null, false, null)))
    }

    @Test
    fun `le dernier message et son etat de lecture sont partages sans historique`() {
        val entry = ChatEntry.AiBubble(42, "Un obstacle devant toi.", null)
        val state = IseeUiState(entries = listOf(entry), selected = 1, speakingEntryId = 42)
        val scene = sharedSceneOf(state, LiveProbeState())
        assertEquals(entry.text, scene?.text)
        assertEquals(true, scene?.speaking)
        assertEquals(false, scene?.fromUser)
    }
}
