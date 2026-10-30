package dev.sam3vision.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesSessionOrderTest {

    @Test
    fun `isee seul bascule la session`() {
        assertTrue(GlassesSessionOrder.isSessionOrder("iSee"))
        assertTrue(GlassesSessionOrder.isSessionOrder("ISEE"))
        assertTrue(GlassesSessionOrder.isSessionOrder("  iSee ! "))
    }

    @Test
    fun `i see en deux mots bascule aussi`() {
        assertTrue(GlassesSessionOrder.isSessionOrder("i see"))
    }

    @Test
    fun `ici ne declenche jamais`() {
        assertFalse(GlassesSessionOrder.isSessionOrder("ici"))
        assertFalse(GlassesSessionOrder.isSessionOrder("qu'est-ce qu'il y a ici ?"))
    }

    @Test
    fun `isee noye dans une question part au LLM`() {
        assertFalse(GlassesSessionOrder.isSessionOrder("demande à iSee ce qu'il voit"))
        assertFalse(GlassesSessionOrder.isSessionOrder("ouvre iSee"))
    }

    @Test
    fun `silence et quasi sosies ne declenchent pas`() {
        assertFalse(GlassesSessionOrder.isSessionOrder(""))
        assertFalse(GlassesSessionOrder.isSessionOrder("easy"))
        assertFalse(GlassesSessionOrder.isSessionOrder("décris-moi la pièce"))
    }
}
