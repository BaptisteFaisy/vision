package dev.sam3vision.core.hazard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Danger suivi : personne + mobilier FR/EN reconnus, le reste ignoré, le poteau muet. */
class HazardConceptsTest {

    @Test
    fun `concepts anglais reconnus`() {
        assertTrue(HazardConcepts.isHazard("barrier"))
        assertTrue(HazardConcepts.isHazard("pole"))
        assertTrue(HazardConcepts.isHazard("person"))
        assertTrue(HazardConcepts.isHazard("human"))
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
        assertTrue(HazardConcepts.isHazard("Personne"))
        assertTrue(HazardConcepts.isHazard("humain"))
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
        assertTrue(HazardConcepts.isHazard("persons"))
        assertTrue(HazardConcepts.isHazard("humans"))
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
        assertEquals("à la personne", HazardConcepts.nameOf("personne")!!.fr)
        assertEquals("barrier", HazardConcepts.nameOf("barrier")!!.en)
        assertEquals("pole", HazardConcepts.nameOf("poteau")!!.en)
        assertEquals("person", HazardConcepts.nameOf("person")!!.en)
        assertEquals("au lampadaire", HazardConcepts.nameOf("street lamp")!!.fr)
        assertEquals("street lamp", HazardConcepts.nameOf("lampadaire")!!.en)
        assertEquals("au panneau", HazardConcepts.nameOf("sign")!!.fr)
        assertEquals("au panneau de signalisation", HazardConcepts.nameOf("traffic sign")!!.fr)
        assertEquals("au potelet", HazardConcepts.nameOf("potelet")!!.fr)
        assertEquals("bollard", HazardConcepts.nameOf("plot")!!.en)
    }

    @Test
    fun `poteaux reconnus FR EN pluriel, reconnus et parles`() {
        assertTrue(HazardConcepts.isPole("pole"))
        assertTrue(HazardConcepts.isPole("poteau"))
        assertTrue(HazardConcepts.isPole("POTEAUX"))
        assertTrue(HazardConcepts.isPole("poles"))
        assertFalse(HazardConcepts.isPole("barrier"))
        assertFalse(HazardConcepts.isPole("person"))
        assertFalse(HazardConcepts.isPole("  "))
        assertTrue(HazardConcepts.isHazard("pole"))
        assertTrue(HazardConcepts.isHazard("poteau"))
        assertTrue(HazardConcepts.isSpoken("pole"))
        assertTrue(HazardConcepts.isSpoken("poteau"))
        assertTrue(HazardConcepts.isSpoken("POTEAUX"))
    }

    @Test
    fun `personnes reconnues FR EN, casse neutralisee, parlees`() {
        assertTrue(HazardConcepts.isPerson("person"))
        assertTrue(HazardConcepts.isPerson("Personne"))
        assertTrue(HazardConcepts.isPerson("humain"))
        assertTrue(HazardConcepts.isPerson("HUMAN"))
        assertTrue(HazardConcepts.isPerson("pedestrian"))
        assertFalse(HazardConcepts.isPerson("barrier"))
        assertFalse(HazardConcepts.isPerson("pole"))
        assertFalse(HazardConcepts.isPerson("  "))
        assertTrue(HazardConcepts.isHazard("person"))
        assertTrue(HazardConcepts.isHazard("humain"))
        assertTrue(HazardConcepts.isSpoken("person"))
        assertTrue(HazardConcepts.isSpoken("Personne"))
        assertTrue(HazardConcepts.isSpoken("pedestrian"))
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
    fun `sorte par label - personne, barriere, divers`() {
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("person"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("Personnes"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("humain"))
        assertEquals(HazardKind.BARRIER, HazardConcepts.kindOf("barrier"))
        assertEquals(HazardKind.BARRIER, HazardConcepts.kindOf("Barrières"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("poteau"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("poles"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("borne"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("clôture"))
    }

    @Test
    fun `autres labels ignores`() {
        assertFalse(HazardConcepts.isHazard("car"))
        assertFalse(HazardConcepts.isHazard("sidewalk"))
        assertFalse(HazardConcepts.isHazard("marche"))
        assertFalse(HazardConcepts.isHazard("  "))
        // Un label inconnu reste muet : ni nom, ni sorte, ni voix.
        assertNull(HazardConcepts.nameOf("trampoline"))
        assertNull(HazardConcepts.nameOf(""))
        assertFalse(HazardConcepts.isSpoken("trampoline"))
    }

    @Test
    fun `foule nommee FR EN, poussette fauteuil trottinette chien enfant`() {
        // Audit foule du 01/11 : ce qui barre le passage même sans piéton debout.
        assertEquals("à la poussette", HazardConcepts.nameOf("stroller")!!.fr)
        assertEquals("stroller", HazardConcepts.nameOf("poussette")!!.en)
        assertEquals("à la poussette", HazardConcepts.nameOf("Poussettes")!!.fr)
        assertEquals("au fauteuil roulant", HazardConcepts.nameOf("wheelchair")!!.fr)
        assertEquals("wheelchair", HazardConcepts.nameOf("fauteuil roulant")!!.en)
        assertEquals("au fauteuil roulant", HazardConcepts.nameOf("fauteuils roulants")!!.fr)
        assertEquals("à la trottinette", HazardConcepts.nameOf("electric scooter")!!.fr)
        assertEquals("scooter", HazardConcepts.nameOf("Trottinettes")!!.en)
        assertEquals("au chien", HazardConcepts.nameOf("dog")!!.fr)
        assertEquals("dog", HazardConcepts.nameOf("Chiens")!!.en)
        assertEquals("à l'enfant", HazardConcepts.nameOf("child")!!.fr)
        assertEquals("child", HazardConcepts.nameOf("Enfants")!!.en)
    }

    @Test
    fun `foule parlee et sorte - les corps se contournent, la trottinette non`() {
        assertTrue(HazardConcepts.isSpoken("dog"))
        assertTrue(HazardConcepts.isSpoken("stroller"))
        assertTrue(HazardConcepts.isSpoken("wheelchair"))
        assertTrue(HazardConcepts.isSpoken("electric scooter"))
        assertTrue(HazardConcepts.isSpoken("child"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("wheelchair"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("stroller"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("poussette"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("dog"))
        assertEquals(HazardKind.PERSON, HazardConcepts.kindOf("enfant"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("electric scooter"))
        assertEquals(HazardKind.OTHER, HazardConcepts.kindOf("trottinette"))
        // L'enfant reste une personne (l'ancien contrat ne bouge pas) ; la
        // poussette se contourne sans être quelqu'un à qui l'on parle.
        assertTrue(HazardConcepts.isPerson("child"))
        assertTrue(HazardConcepts.isPerson("enfant"))
        assertFalse(HazardConcepts.isPerson("stroller"))
        assertFalse(HazardConcepts.isPerson("dog"))
    }
}
