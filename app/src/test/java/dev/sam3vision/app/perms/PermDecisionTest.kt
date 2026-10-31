package dev.sam3vision.app.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Règles et textes de permission qui restent lus par la production (sans Android). */
class PermDecisionTest {

    @Test
    fun `arriere-plan seulement apres avant-plan`() {
        assertFalse(canAskBackground(fgGranted = false))
        assertTrue(canAskBackground(fgGranted = true))
    }

    @Test
    fun `libelle micro reprend l historique des bandeaux`() {
        assertEquals("Microphone not allowed", PermTexts.MIC_DENIED)
    }
}
