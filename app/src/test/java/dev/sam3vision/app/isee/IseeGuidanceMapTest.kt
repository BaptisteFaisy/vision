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

    @Test
    fun `en guidage, le transcript du user remplace la boule au milieu`() {
        val speaking = IseeUiState(holding = true, userSpeaking = true, liveTranscript = "où est la gare")

        assertTrue(showGuidanceTranscript(guiding, speaking))
    }

    @Test
    fun `en guidage sans parole, aucun transcript au milieu`() {
        assertFalse(showGuidanceTranscript(guiding, IseeUiState()))
        assertFalse(showGuidanceTranscript(guiding, IseeUiState(holding = true, userSpeaking = false, liveTranscript = "où")))
    }

    @Test
    fun `en guidage, parole sans transcript — aucun transcript, rien a afficher`() {
        val silent = IseeUiState(holding = true, userSpeaking = true, liveTranscript = "   ")

        assertFalse(showGuidanceTranscript(guiding, silent))
    }

    @Test
    fun `en guidage camera ouverte, la boule s'efface entre la rue et la carte`() {
        assertTrue(hideGuidanceOrb(guiding, true))
    }

    @Test
    fun `en guidage camera fermee, la boule reste au milieu`() {
        assertFalse(hideGuidanceOrb(guiding, false))
    }

    @Test
    fun `hors guidage, la camera ne cache jamais la boule`() {
        assertFalse(hideGuidanceOrb(VoiceNavUiState.Idle, true))
        assertFalse(hideGuidanceOrb(VoiceNavUiState.Confirming("la gare", null, true, null), true))
        assertFalse(hideGuidanceOrb(VoiceNavUiState.Arrived("la gare", null), true))
    }

    @Test
    fun `hors guidage, la parole ne remplace jamais la boule`() {
        val speaking = IseeUiState(holding = true, userSpeaking = true, liveTranscript = "où est la gare")

        assertFalse(showGuidanceTranscript(VoiceNavUiState.Idle, speaking))
        assertFalse(showGuidanceTranscript(VoiceNavUiState.Confirming("la gare", null, true, null), speaking))
        assertFalse(showGuidanceTranscript(VoiceNavUiState.Arrived("la gare", null), speaking))
    }
}
