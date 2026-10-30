package dev.sam3vision.core.cycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cycles suivis : vélo et trottinette FR/EN reconnus, le reste ignoré. */
class CycleConceptsTest {

    @Test
    fun `concepts anglais reconnus`() {
        assertTrue(CycleConcepts.isCycle("bicycle"))
        assertTrue(CycleConcepts.isCycle("bike"))
        assertTrue(CycleConcepts.isCycle("scooter"))
        assertTrue(CycleConcepts.isCycle("electric scooter"))
    }

    @Test
    fun `concepts francais reconnus, accents et casse neutralises`() {
        assertTrue(CycleConcepts.isCycle("Vélo"))
        assertTrue(CycleConcepts.isCycle("VELO"))
        assertTrue(CycleConcepts.isCycle("trottinette"))
        assertTrue(CycleConcepts.isCycle("TROTTINETTE"))
        assertTrue(CycleConcepts.isCycle("Trottinette électrique"))
    }

    @Test
    fun `pluriels reconnus`() {
        assertTrue(CycleConcepts.isCycle("bicycles"))
        assertTrue(CycleConcepts.isCycle("Bikes"))
        assertTrue(CycleConcepts.isCycle("vélos"))
        assertTrue(CycleConcepts.isCycle("scooters"))
        assertTrue(CycleConcepts.isCycle("electric scooters"))
        assertTrue(CycleConcepts.isCycle("trottinettes"))
    }

    @Test
    fun `noms parles`() {
        assertEquals("vélo", CycleConcepts.nameOf("bicycle")!!.fr)
        assertEquals("bicycle", CycleConcepts.nameOf("vélo")!!.en)
        assertEquals("arrêté", CycleConcepts.nameOf("bike")!!.stoppedFr)
        assertEquals("trottinette", CycleConcepts.nameOf("scooter")!!.fr)
        assertEquals("scooter", CycleConcepts.nameOf("trottinette")!!.en)
        assertEquals("arrêtée", CycleConcepts.nameOf("electric scooter")!!.stoppedFr)
    }

    @Test
    fun `autres labels ignores dont la moto`() {
        assertFalse(CycleConcepts.isCycle("car"))
        assertFalse(CycleConcepts.isCycle("motorcycle"))
        assertFalse(CycleConcepts.isCycle("moto"))
        assertFalse(CycleConcepts.isCycle("person"))
        assertFalse(CycleConcepts.isCycle("sidewalk"))
        assertFalse(CycleConcepts.isCycle(""))
        assertFalse(CycleConcepts.isCycle("  "))
    }
}
