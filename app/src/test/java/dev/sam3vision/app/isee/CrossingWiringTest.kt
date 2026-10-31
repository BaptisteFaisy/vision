package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.settings.Settings
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraverseVeto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossingWiringTest {

    @Test
    fun `mode coupe libelle OFF`() {
        assertEquals("Crossing: OFF", crossingButtonLabel(false, null))
        assertEquals("Crossing: OFF", crossingButtonLabel(false, CrossingState.Green))
    }

    @Test
    fun `mode actif sans lecture dit rouge, binaire`() {
        assertEquals("Crossing: RED", crossingButtonLabel(true, null))
    }

    @Test
    fun `un passage pieton propose sans activer`() {
        // La proposition ne remplace pas le libelle d'etat : hors mode, elle
        // annonce ce qui est reconnu et laisse la main a l'utilisateur.
        assertEquals("Crosswalk — cross?", crossingButtonLabel(false, null, offer = true))
        assertEquals("Crosswalk — cross?", crossingButtonLabel(false, CrossingState.Green, offer = true))
    }

    @Test
    fun `en mode actif la proposition ne change rien`() {
        assertEquals("Crossing: GREEN", crossingButtonLabel(true, CrossingState.Green, offer = true))
        assertEquals("Crossing: RED", crossingButtonLabel(true, CrossingState.Red, offer = true))
    }

    @Test
    fun `sans proposition le libelle reste celui d avant`() {
        assertEquals("Crossing: OFF", crossingButtonLabel(false, null, offer = false))
    }

    @Test
    fun `mode actif libelle l etat du feu`() {
        assertEquals("Crossing: GREEN", crossingButtonLabel(true, CrossingState.Green))
        assertEquals("Crossing: RED", crossingButtonLabel(true, CrossingState.Red))
    }

    @Test
    fun `bascule traversee garde les memes prompts, memes masques`() = runTest {
        val (probe, _) = probeHarness(this)
        val before = probe.state.value.conceptsSent
        probe.setCrossingActive(Settings.Default, true)
        assertTrue(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
        probe.setCrossingActive(Settings.Default, false)
        assertFalse(probe.state.value.crossingActive)
        assertEquals(before, probe.state.value.conceptsSent)
    }

    @Test
    fun `mode coupe ne publie ni phrase ni phase`() {
        // Sortie avant toute lecture : la sonde reste muette et le bandeau absent.
        val patch = crossingPatchOf(
            CrossingAnnouncer(), TraverseVeto(), false,
            LiveFrame(100, 100, 0L, emptyList()), LensSpec.Default, "", 0L, SpeechLanguage.Fr,
        )
        assertNull(patch)
    }
}
