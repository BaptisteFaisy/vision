package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.session.DeviceSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Traduction des états du SDK DAT vers les phases affichées. `PAUSED` reste « en cours » :
 * une pause de session (paire reposée, lien rétabli) reprend d'elle-même, et l'afficher
 * « terminée » donnerait un bouton de redémarrage trompeur.
 */
class GlassesPhaseMappingTest {

    @Test
    fun `chaque etat du SDK a une phase affichee`() {
        assertEquals(SessionPhase.IDLE, DeviceSessionState.IDLE.toPhase())
        assertEquals(SessionPhase.STARTING, DeviceSessionState.STARTING.toPhase())
        assertEquals(SessionPhase.STARTED, DeviceSessionState.STARTED.toPhase())
        assertEquals(SessionPhase.STARTING, DeviceSessionState.PAUSED.toPhase())
        assertEquals(SessionPhase.STOPPED, DeviceSessionState.STOPPING.toPhase())
        assertEquals(SessionPhase.STOPPED, DeviceSessionState.STOPPED.toPhase())
    }

    @Test
    fun `une session en pause ne propose pas de redemarrage`() {
        val paused = GlassesUiState(registered = true, phase = DeviceSessionState.PAUSED.toPhase())
        assertFalse(paused.canConnect)
    }
}