package dev.sam3vision.core.hazard

import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Clôture suivie : concepts FR/EN reconnus, portail sorti, le reste ignoré. */
class FenceOpeningConceptsTest {

    @Test
    fun `concepts anglais reconnus`() {
        assertTrue(FenceOpeningConcepts.isFence("fence"))
        assertTrue(FenceOpeningConcepts.isFence("barrier"))
        assertFalse("portail sorti v4", FenceOpeningConcepts.isFence("gate"))
    }

    @Test
    fun `concepts francais reconnus, accents et casse neutralises`() {
        assertTrue(FenceOpeningConcepts.isFence("Clôture"))
        assertTrue(FenceOpeningConcepts.isFence("grillage"))
        assertTrue(FenceOpeningConcepts.isFence("Barrière"))
        assertFalse("portail sorti v4", FenceOpeningConcepts.isFence("PORTAIL"))
    }

    @Test
    fun `pluriels et composes reconnus`() {
        assertTrue(FenceOpeningConcepts.isFence("fences"))
        assertTrue(FenceOpeningConcepts.isFence("grillages"))
        assertTrue(FenceOpeningConcepts.isFence("barrières"))
        assertFalse("portail sorti v4", FenceOpeningConcepts.isFence("portails"))
        assertTrue(FenceOpeningConcepts.isFence("clôture grillagée"))
    }

    @Test
    fun `portail sorti, plus distingue de rien`() {
        assertFalse(FenceOpeningConcepts.isGate("gate"))
        assertFalse(FenceOpeningConcepts.isGate("Portails"))

        assertFalse(FenceOpeningConcepts.isGate("fence"))
        assertFalse(FenceOpeningConcepts.isGate("grillage"))
        assertFalse(FenceOpeningConcepts.isGate("barrier"))
    }

    @Test
    fun `hauteurs stenope barriere grillage, portail sorti`() {
        assertEquals(1.00f, FenceOpeningConcepts.heightMeters("barrier")!!, 0.001f)
        assertEquals(1.50f, FenceOpeningConcepts.heightMeters("fence")!!, 0.001f)
        assertEquals(1.00f, FenceOpeningConcepts.heightMeters("Barrière")!!, 0.001f)
        assertEquals(1.50f, FenceOpeningConcepts.heightMeters("grillage")!!, 0.001f)
        assertNull(FenceOpeningConcepts.heightMeters("gate"))
        assertNull(FenceOpeningConcepts.heightMeters("portail"))
    }

    @Test
    fun `autres labels ignores`() {
        assertFalse(FenceOpeningConcepts.isFence("person"))
        assertFalse(FenceOpeningConcepts.isFence("car"))
        assertFalse(FenceOpeningConcepts.isFence("sidewalk"))
        assertFalse(FenceOpeningConcepts.isFence("pole"))
        assertFalse(FenceOpeningConcepts.isFence("marche"))
        assertFalse(FenceOpeningConcepts.isFence("  "))
    }

    @Test
    fun `prompts sous les plafonds du direct`() {
        val prompts = FenceOpeningConcepts.PROMPTS

        assertTrue(prompts.size <= LivePrompts.MAX)
        prompts.forEach { prompt ->
            assertTrue(prompt.isNotBlank())
            assertTrue(prompt.length <= LivePrompts.MAX_LENGTH)
        }
    }
}
