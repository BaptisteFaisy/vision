package dev.sam3vision.app.voicenav

import dev.sam3vision.core.nav.GuidanceUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décisions pures du cycle de vie fond/premier plan (JUnit seul, sans
 * Android) : seul le guidage actif mérite le service. Le nom du service
 * W1 est verrouillé ici — si W1 le nomme autrement, le fond reste inerte
 * (pas de service) au lieu de planter.
 */
class GuidanceLifecycleTest {

    private val guiding = VoiceNavUiState.Guiding(
        destination = "la gare",
        summary = null,
        update = GuidanceUpdate("Tout droit", 100, 2_000, 900),
    )

    @Test
    fun `guidage actif demande le service de fond`() {
        assertTrue(GuidanceLifecycle.wantsBackground(guiding))
    }

    @Test
    fun `repos ne demande pas le service de fond`() {
        assertFalse(GuidanceLifecycle.wantsBackground(VoiceNavUiState.Idle))
    }

    @Test
    fun `confirmation ne demande pas le service de fond`() {
        val confirming = VoiceNavUiState.Confirming("la gare", null, false, null)
        assertFalse(GuidanceLifecycle.wantsBackground(confirming))
    }

    @Test
    fun `nom du service W1 verrouille le contrat inter-lots`() {
        assertEquals(
            "dev.sam3vision.app.voicenav.GuidanceTrackingService",
            GuidanceLifecycle.SERVICE_CLASS,
        )
    }
}
