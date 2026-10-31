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

    @Test
    fun `crosswalk on active`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("crosswalk on"))
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("Crosswalk on!"))
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("turn the crosswalk on"))
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("crosswalk mode on"))
    }

    @Test
    fun `crosswalk off coupe`() {
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("crosswalk off"))
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("turn the crosswalk off"))
        assertEquals(CrossingVoiceAction.Deactivate, CrossingVoiceCommand.parse("crosswalk mode off"))
    }

    @Test
    fun `crosswalk seul n est pas un ordre`() {
        assertNull(CrossingVoiceCommand.parse("crosswalk"))
        assertNull(CrossingVoiceCommand.parse("Find the crosswalk"))
        assertNull(CrossingVoiceCommand.parse("où est le crosswalk le plus proche ?"))
    }

    @Test
    fun `les phrases dites par le guidage ne redebranchent pas`() {
        // Le micro peut réentendre le haut-parleur : ces phrases contiennent
        // « crosswalk » (voire « on » devant) sans être des ordres.
        assertNull(CrossingVoiceCommand.parse("Crosswalk in 50 meters"))
        assertNull(CrossingVoiceCommand.parse("On the crosswalk."))
    }

    @Test
    fun `traverser le crosswalk active par traverser`() {
        assertEquals(CrossingVoiceAction.Activate, CrossingVoiceCommand.parse("traverser le crosswalk"))
    }
}
