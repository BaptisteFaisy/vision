package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossingVoiceCommandTest {

    @Test
    fun `traverser seul active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("traverser"))
    }

    @Test
    fun `je veux traverser active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("je veux traverser"))
    }

    @Test
    fun `on traverse active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("on traverse"))
    }

    @Test
    fun `la traversee avec accent et casse active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("Active la Traversée !"))
    }

    @Test
    fun `a travers la piece n active pas`() {
        assertNull(CrossingVoiceCommand.parse("à travers la pièce"))
    }

    @Test
    fun `une question ordinaire n active pas`() {
        assertNull(CrossingVoiceCommand.parse("décris-moi la pièce"))
        assertNull(CrossingVoiceCommand.parse(""))
        assertNull(CrossingVoiceCommand.parse("   "))
    }

    @Test
    fun `arrete la traversee coupe`() {
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("arrête la traversée"))
    }

    @Test
    fun `stop traverser coupe`() {
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("stop traverser"))
    }

    @Test
    fun `cross the street active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("cross the street"))
    }

    @Test
    fun `crossing mode on active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("crossing mode on"))
    }

    @Test
    fun `crossing mode off coupe`() {
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("crossing mode off"))
    }
}
