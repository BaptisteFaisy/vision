package dev.sam3vision.app.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Décision pure du maintien de fond (JUnit seul, sans Android) : une session
 * lunettes en cours (ou en démarrage) survit à la veille du téléphone via le
 * service de premier plan ; au repos ou arrêtée, aucun service.
 */
class GlassesSessionLifecycleTest {

    @Test
    fun `session demarree demande le maintien de fond`() {
        assertTrue(GlassesSessionLifecycle.wantsBackground(SessionPhase.STARTED))
    }

    @Test
    fun `session en demarrage demande le maintien de fond`() {
        assertTrue(GlassesSessionLifecycle.wantsBackground(SessionPhase.STARTING))
    }

    @Test
    fun `repos ne demande pas le maintien de fond`() {
        assertFalse(GlassesSessionLifecycle.wantsBackground(SessionPhase.IDLE))
    }

    @Test
    fun `session arretee ne demande pas le maintien de fond`() {
        assertFalse(GlassesSessionLifecycle.wantsBackground(SessionPhase.STOPPED))
    }
}
