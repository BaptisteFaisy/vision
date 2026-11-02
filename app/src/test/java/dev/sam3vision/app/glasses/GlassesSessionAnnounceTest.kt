package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Annonce vocale d'ouverture de session : le téléphone est en poche, l'utilisateur
 * entend que les lunettes sont prêtes. L'annonce ne part qu'à la transition vers
 * `STARTED`, jamais sur un état répété. Aucun SDK, aucun Android.
 */
class GlassesSessionAnnounceTest {

    @Test
    fun `l ouverture de session annonce connected`() {
        assertEquals("connected", SESSION_CONNECTED_ANNOUNCEMENT)
        assertTrue(shouldAnnounceSessionConnected(SessionPhase.STARTING, SessionPhase.STARTED))
        assertTrue(shouldAnnounceSessionConnected(SessionPhase.IDLE, SessionPhase.STARTED))
        assertTrue(shouldAnnounceSessionConnected(SessionPhase.STOPPED, SessionPhase.STARTED))
    }

    @Test
    fun `un STARTED repete ne re-annonce pas`() {
        assertFalse(shouldAnnounceSessionConnected(SessionPhase.STARTED, SessionPhase.STARTED))
        assertFalse(shouldAnnounceSessionConnected(SessionPhase.STARTED, SessionPhase.STOPPED))
        assertFalse(shouldAnnounceSessionConnected(SessionPhase.IDLE, SessionPhase.STARTING))
        assertFalse(shouldAnnounceSessionConnected(SessionPhase.STARTING, SessionPhase.STOPPED))
    }
}
