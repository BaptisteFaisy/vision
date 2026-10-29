package dev.sam3vision.core.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mobilier suivi : concepts FR/EN reconnus, le reste ignoré. */
class HazardConceptsTest {

    @Test
    fun `concepts anglais reconnus`() {
        assertTrue(HazardConcepts.isHazard("barrier"))
        assertTrue(HazardConcepts.isHazard("pole"))
        assertTrue(HazardConcepts.isHazard("bollard"))
        assertTrue(HazardConcepts.isHazard("fence"))
        assertTrue(HazardConcepts.isHazard("gate"))
    }

    @Test
    fun `concepts francais reconnus, accents et casse neutralises`() {
        assertTrue(HazardConcepts.isHazard("Barrière"))
        assertTrue(HazardConcepts.isHazard("POTEAU"))
        assertTrue(HazardConcepts.isHazard("borne"))
        assertTrue(HazardConcepts.isHazard("Clôture"))
        assertTrue(HazardConcepts.isHazard("portail"))
    }

    @Test
    fun `pluriels reconnus`() {
        assertTrue(HazardConcepts.isHazard("barriers"))
        assertTrue(HazardConcepts.isHazard("poteaux"))
        assertTrue(HazardConcepts.isHazard("portails"))
    }

    @Test
    fun `noms parles`() {
        assertEquals("à la barrière", HazardConcepts.nameOf("barrier")!!.fr)
        assertEquals("au poteau", HazardConcepts.nameOf("poteau")!!.fr)
        assertEquals("barrier", HazardConcepts.nameOf("barrier")!!.en)
        assertEquals("pole", HazardConcepts.nameOf("poteau")!!.en)
    }

    @Test
    fun `autres labels ignores`() {
        assertFalse(HazardConcepts.isHazard("person"))
        assertFalse(HazardConcepts.isHazard("car"))
        assertFalse(HazardConcepts.isHazard("sidewalk"))
        assertFalse(HazardConcepts.isHazard("marche"))
        assertFalse(HazardConcepts.isHazard("  "))
    }
}
