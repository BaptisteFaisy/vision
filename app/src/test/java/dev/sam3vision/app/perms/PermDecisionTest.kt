package dev.sam3vision.app.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table de décision du flux permissions : pure, sans Android. */
class PermDecisionTest {

    @Test
    fun `accordee on continue`() {
        assertEquals(PermStep.PROCEED, nextStep(granted = true, askedBefore = false, showRationale = false))
        assertEquals(PermStep.PROCEED, nextStep(granted = true, askedBefore = true, showRationale = true))
    }

    @Test
    fun `jamais demandee on demande sans expliquer`() {
        assertEquals(PermStep.ASK, nextStep(granted = false, askedBefore = false, showRationale = false))
    }

    @Test
    fun `refusee redemandable on explique`() {
        assertEquals(PermStep.RATIONALE, nextStep(granted = false, askedBefore = true, showRationale = true))
    }

    @Test
    fun `ne plus demander renvoie vers les reglages`() {
        assertEquals(PermStep.SETTINGS, nextStep(granted = false, askedBefore = true, showRationale = false))
    }

    @Test
    fun `position avant-plan fine ou approximative`() {
        assertTrue(fgGranted(fine = true, coarse = true))
        assertTrue(fgGranted(fine = true, coarse = false))
        assertTrue(fgGranted(fine = false, coarse = true))
        assertFalse(fgGranted(fine = false, coarse = false))
    }

    @Test
    fun `arriere-plan seulement apres avant-plan`() {
        assertFalse(canAskBackground(fgGranted = false))
        assertTrue(canAskBackground(fgGranted = true))
    }

    @Test
    fun `uri des reglages au format package`() {
        assertEquals("package:dev.sam3vision.app", appSettingsUri("dev.sam3vision.app"))
    }

    @Test
    fun `textes non vides pour chaque permission`() {
        PermKind.entries.forEach { kind ->
            val copy = copyFor(kind)
            assertTrue("$kind titre", copy.rationaleTitle.isNotBlank())
            assertTrue("$kind explication", copy.rationaleMessage.isNotBlank())
            assertTrue("$kind repli reglages", copy.settingsMessage.isNotBlank())
            assertTrue("$kind refus", copy.deniedMessage.isNotBlank())
            assertTrue("$kind bouton", copy.confirmLabel.isNotBlank())
        }
    }

    @Test
    fun `repli reglages cite ne plus demander`() {
        PermKind.entries.forEach { kind ->
            assertTrue("$kind", copyFor(kind).settingsMessage.contains("Ne plus demander"))
        }
    }

    @Test
    fun `arriere-plan propose continuer pas autoriser`() {
        assertEquals(PermTexts.CONTINUE, copyFor(PermKind.LOCATION_BG).confirmLabel)
        assertEquals(PermTexts.ALLOW, copyFor(PermKind.MIC).confirmLabel)
        assertEquals(PermTexts.ALLOW, copyFor(PermKind.LOCATION_FG).confirmLabel)
    }

    @Test
    fun `libelle micro reprend l historique des bandeaux`() {
        assertEquals("Micro non autorisé", PermTexts.MIC_DENIED)
        assertEquals(PermTexts.MIC_DENIED, copyFor(PermKind.MIC).deniedMessage)
    }
}
