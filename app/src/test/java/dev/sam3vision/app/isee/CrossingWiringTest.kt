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
    fun `mode actif libelle l etat du feu`() {
        assertEquals("Traversée : VERT", crossingButtonLabel(true, CrossingState.Green))
        assertEquals("Traversée : ROUGE", crossingButtonLabel(true, CrossingState.Red))
    }
}
