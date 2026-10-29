package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtre panneaux à hauteur d'humain : quoi garder, quoi taire. */
class SignConceptsTest {

    @Test
    fun `isSign reconnait anglais francais et pluriels`() {
        assertTrue(SignConcepts.isSign("sign"))
        assertTrue(SignConcepts.isSign("Signs"))
        assertTrue(SignConcepts.isSign("panneau"))
        assertTrue(SignConcepts.isSign("PANNEAUX"))
        assertTrue(SignConcepts.isSign("traffic sign"))
        assertTrue(SignConcepts.isSign("billboard"))
    }

    @Test
    fun `isSign refuse le reste`() {
        assertFalse(SignConcepts.isSign("person"))
        assertFalse(SignConcepts.isSign("traffic light"))
        assertFalse(SignConcepts.isSign("wall"))
        assertFalse(SignConcepts.isSign(""))
        assertFalse(SignConcepts.isSign("   "))
    }

    @Test
    fun `panneau haut du cadre ecarte`() {
        assertFalse(SignConcepts.keep("sign", centerY = 0.05f, meters = 2.0f))
        assertFalse(SignConcepts.keep("panneau", centerY = 0.21f, meters = 1.0f))
    }

    @Test
    fun `panneau a hauteur d humain garde`() {
        assertTrue(SignConcepts.keep("sign", centerY = 0.5f, meters = 2.0f))
        assertTrue(SignConcepts.keep("panneau", centerY = 0.22f, meters = 1.0f))
    }

    @Test
    fun `panneau lointain ecarte`() {
        assertFalse(SignConcepts.keep("sign", centerY = 0.5f, meters = 10.0f))
    }

    @Test
    fun `distance inconnue garde par prudence`() {
        assertTrue(SignConcepts.keep("sign", centerY = 0.5f, meters = null))
    }

    @Test
    fun `non-panneau toujours garde`() {
        assertTrue(SignConcepts.keep("person", centerY = 0.05f, meters = 10.0f))
        assertTrue(SignConcepts.keep("traffic light", centerY = 0.05f, meters = 10.0f))
    }

    @Test
    fun `guidage ignore le panneau haut`() {
        val high = PlacedLabel("sign", centerX = 0.5f, centerY = 0.05f, meters = 2.0f)
        val decision = Guidance.decide(listOf(high))
        assertFalse(decision.blocked)
    }

    @Test
    fun `guidage garde le panneau a hauteur d humain`() {
        val headHigh = PlacedLabel("sign", centerX = 0.5f, centerY = 0.5f, meters = 2.0f)
        val decision = Guidance.decide(listOf(headHigh))
        assertTrue(decision.blocked)
    }
}
