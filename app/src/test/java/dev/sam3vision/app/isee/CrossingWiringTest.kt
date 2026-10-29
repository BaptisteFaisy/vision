package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingAnnouncer
import dev.sam3vision.core.crossing.CrossingState
import dev.sam3vision.core.distance.LensSpec
import dev.sam3vision.core.model.LiveFrame
import dev.sam3vision.core.speech.SpeechLanguage
import dev.sam3vision.core.traverse.TraverseVeto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CrossingWiringTest {

    @Test
    fun `mode coupe libelle OFF`() {
        assertEquals("Traversée : OFF", crossingButtonLabel(false, null))
        assertEquals("Traversée : OFF", crossingButtonLabel(false, CrossingState.Green))
    }

    @Test
    fun `mode actif sans etat libelle points`() {
        assertEquals("Traversée : …", crossingButtonLabel(true, null))
        assertEquals("Traversée : …", crossingButtonLabel(true, CrossingState.Unknown))
    }

    @Test
    fun `un passage pieton propose sans activer`() {
        // La proposition ne remplace pas le libelle d'etat : hors mode, elle
        // annonce ce qui est reconnu et laisse la main a l'utilisateur.
        assertEquals("Passage piéton — traversée ?", crossingButtonLabel(false, null, offer = true))
        assertEquals("Passage piéton — traversée ?", crossingButtonLabel(false, CrossingState.Green, offer = true))
    }

    @Test
    fun `en mode actif la proposition ne change rien`() {
        assertEquals("Traversée : VERT", crossingButtonLabel(true, CrossingState.Green, offer = true))
        assertEquals("Traversée : ROUGE", crossingButtonLabel(true, CrossingState.Red, offer = true))
    }

    @Test
    fun `sans proposition le libelle reste celui d avant`() {
        assertEquals("Traversée : OFF", crossingButtonLabel(false, null, offer = false))
    }

    @Test
    fun `mode actif libelle l etat du feu`() {
        assertEquals("Traversée : VERT", crossingButtonLabel(true, CrossingState.Green))
        assertEquals("Traversée : ROUGE", crossingButtonLabel(true, CrossingState.Red))
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
