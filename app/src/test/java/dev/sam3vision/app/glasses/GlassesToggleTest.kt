package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class GlassesToggleTest {

    @Test
    fun `session en cours ou en demarrage, iSee arrete lien ou pas`() {
        assertEquals(SessionToggle.STOP, toggleAction(SessionPhase.STARTED, linked = true))
        assertEquals(SessionToggle.STOP, toggleAction(SessionPhase.STARTED, linked = false))
        assertEquals(SessionToggle.STOP, toggleAction(SessionPhase.STARTING, linked = true))
        assertEquals(SessionToggle.STOP, toggleAction(SessionPhase.STARTING, linked = false))
    }

    @Test
    fun `sans session et paire liee, iSee ouvre`() {
        assertEquals(SessionToggle.START, toggleAction(SessionPhase.IDLE, linked = true))
        assertEquals(SessionToggle.START, toggleAction(SessionPhase.STOPPED, linked = true))
    }

    @Test
    fun `sans session et sans lien, iSee refuse au lieu d attendre`() {
        assertEquals(SessionToggle.NOT_LINKED, toggleAction(SessionPhase.IDLE, linked = false))
        assertEquals(SessionToggle.NOT_LINKED, toggleAction(SessionPhase.STOPPED, linked = false))
    }

    @Test
    fun `confirmations de bascule, info sauf refus en avertissement`() {
        assertEquals(MessageKind.INFO, GlassesMessages.toggleStarted().kind)
        assertEquals(MessageKind.INFO, GlassesMessages.toggleStopped().kind)
        assertEquals(MessageKind.WARNING, GlassesMessages.toggleNotLinked().kind)
    }
}
