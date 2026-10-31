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
        assertTrue(HazardConcepts.isHazard("street lamp"))
        assertTrue(HazardConcepts.isHazard("sign"))
        assertTrue(HazardConcepts.isHazard("traffic sign"))
    }

    @Test
    fun `concepts francais reconnus, accents et casse neutralises`() {
        assertTrue(HazardConcepts.isHazard("Barrière"))
        assertTrue(HazardConcepts.isHazard("POTEAU"))
        assertTrue(HazardConcepts.isHazard("borne"))
        assertTrue(HazardConcepts.isHazard("Clôture"))
        assertTrue(HazardConcepts.isHazard("portail"))
        assertTrue(HazardConcepts.isHazard("Lampadaire"))
        assertTrue(HazardConcepts.isHazard("RÉVERBÈRE"))
        assertTrue(HazardConcepts.isHazard("Panneau"))
        assertTrue(HazardConcepts.isHazard("panneaux"))
        assertTrue(HazardConcepts.isHazard("Potelet"))
        assertTrue(HazardConcepts.isHazard("plot"))
    }

    @Test
    fun `pluriels reconnus`() {
        assertTrue(HazardConcepts.isHazard("barriers"))
        assertTrue(HazardConcepts.isHazard("poteaux"))
        assertTrue(HazardConcepts.isHazard("portails"))
        assertTrue(HazardConcepts.isHazard("street lamps"))
        assertTrue(HazardConcepts.isHazard("lampadaires"))
        assertTrue(HazardConcepts.isHazard("signs"))
        assertTrue(HazardConcepts.isHazard("traffic signs"))
        assertTrue(HazardConcepts.isHazard("potelets"))
        assertTrue(HazardConcepts.isHazard("plots"))
    }

    @Test
    fun `noms parles`() {
        assertEquals("à la barrière", HazardConcepts.nameOf("barrier")!!.fr)
        assertEquals("au poteau", HazardConcepts.nameOf("poteau")!!.fr)
        assertEquals("barrier", HazardConcepts.nameOf("barrier")!!.en)
        assertEquals("pole", HazardConcepts.nameOf("poteau")!!.en)
        assertEquals("au lampadaire", HazardConcepts.nameOf("street lamp")!!.fr)
        assertEquals("street lamp", HazardConcepts.nameOf("lampadaire")!!.en)
        assertEquals("au panneau", HazardConcepts.nameOf("sign")!!.fr)
        assertEquals("au panneau de signalisation", HazardConcepts.nameOf("traffic sign")!!.fr)
        assertEquals("au potelet", HazardConcepts.nameOf("potelet")!!.fr)
        assertEquals("bollard", HazardConcepts.nameOf("plot")!!.en)
    }

    @Test
    fun `poteaux reconnus FR EN pluriel`() {
        assertTrue(HazardConcepts.isPole("pole"))
        assertTrue(HazardConcepts.isPole("poteau"))
        assertTrue(HazardConcepts.isPole("POTEAUX"))
        assertTrue(HazardConcepts.isPole("poles"))
        assertFalse(HazardConcepts.isPole("barrier"))
        assertFalse(HazardConcepts.isPole("person"))
        assertFalse(HazardConcepts.isPole("  "))
    }

    @Test
    fun `barrieres reconnues FR EN pluriel`() {
        assertTrue(HazardConcepts.isBarrier("barrier"))
        assertTrue(HazardConcepts.isBarrier("barrière"))
        assertTrue(HazardConcepts.isBarrier("BARRIERS"))
        assertFalse(HazardConcepts.isBarrier("pole"))
        assertFalse(HazardConcepts.isBarrier("borne"))
        assertFalse(HazardConcepts.isBarrier("person"))
        assertFalse(HazardConcepts.isBarrier("  "))
    }

    @Test
    fun `sorte par label - poteau, barriere, divers`() {
        assertEquals(HazardKind.POLE, HazardConcepts.kindOf("poteau"))
        assertEquals(HazardKind.POLE, HazardConcepts.kindOf("poles"))
        assertEquals(HazardKind.BARRIER, HazardConcepts.kindOf("barrier"))
        assertEquals(HazardKind.BARRIER, HazardConcepts.kindOf("Barrières"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("borne"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("clôture"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("person"))
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
