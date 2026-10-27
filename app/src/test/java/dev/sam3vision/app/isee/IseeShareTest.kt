package dev.sam3vision.app.isee

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
    fun `le dernier message et son etat de lecture sont partages sans historique`() {
        val entry = ChatEntry.AiBubble(42, "Un obstacle devant toi.", null)
        val state = IseeUiState(entries = listOf(entry), selected = 1, speakingEntryId = 42)
        val scene = sharedSceneOf(state, LiveProbeState())
        assertEquals(entry.text, scene?.text)
        assertEquals(true, scene?.speaking)
        assertEquals(false, scene?.fromUser)
    }
}
