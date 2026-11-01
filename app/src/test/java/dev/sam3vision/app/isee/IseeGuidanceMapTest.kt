package dev.sam3vision.app.isee

import dev.sam3vision.app.voicenav.VoiceNavUiState
import dev.sam3vision.core.nav.GuidanceUpdate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IseeGuidanceMapTest {
    private val guiding = VoiceNavUiState.Guiding(
        destination = "la gare",
        summary = null,
        update = GuidanceUpdate("Tout droit", 100, 2_000, 900),
    )

    @Test
    fun `le calcul et la carte suivie recouvrent la scene`() {
        assertTrue(isGuidanceOverlay(guiding))
        assertTrue(isGuidanceOverlay(VoiceNavUiState.Confirming("la gare", null, true, null)))
    }

    @Test
    fun `l'attente et l'arrivee laissent la scene et la barre du bas`() {
        assertFalse(isGuidanceOverlay(VoiceNavUiState.Idle))
        assertFalse(isGuidanceOverlay(VoiceNavUiState.Arrived("la gare", null)))
    }
}
