package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import org.junit.Assert.assertEquals
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
}
