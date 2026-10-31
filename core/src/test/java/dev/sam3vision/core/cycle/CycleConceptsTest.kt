package dev.sam3vision.core.cycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cycles suivis : vélo, trottinette et 2-roues FR/EN reconnus, le reste ignoré. */
class CycleConceptsTest {

    @Test
    fun `concepts anglais reconnus`() {
        assertTrue(CycleConcepts.isCycle("bicycle"))
        assertTrue(CycleConcepts.isCycle("bike"))
        assertTrue(CycleConcepts.isCycle("scooter"))
        assertTrue(CycleConcepts.isCycle("electric scooter"))
        assertTrue(CycleConcepts.isCycle("kick scooter"))
        assertTrue(CycleConcepts.isCycle("electric kick scooter"))
        assertTrue(CycleConcepts.isCycle("e-scooter"))
        assertTrue(CycleConcepts.isCycle("escooter"))
        assertTrue(CycleConcepts.isCycle("electric bike"))
        assertTrue(CycleConcepts.isCycle("electric bicycle"))
        assertTrue(CycleConcepts.isCycle("e-bike"))
        assertTrue(CycleConcepts.isCycle("ebike"))
        assertTrue(CycleConcepts.isCycle("two-wheeler"))
        assertTrue(CycleConcepts.isCycle("Two Wheeler"))
    }

    @Test
    fun `concepts francais reconnus, accents et casse neutralises`() {
        assertTrue(CycleConcepts.isCycle("Vélo"))
        assertTrue(CycleConcepts.isCycle("VELO"))
        assertTrue(CycleConcepts.isCycle("trottinette"))
        assertTrue(CycleConcepts.isCycle("TROTTINETTE"))
        assertTrue(CycleConcepts.isCycle("Trottinette électrique"))
        assertTrue(CycleConcepts.isCycle("Vélo électrique"))
        assertTrue(CycleConcepts.isCycle("bicyclette"))
        assertTrue(CycleConcepts.isCycle("BICYCLETTE"))
        assertTrue(CycleConcepts.isCycle("2-roues"))
        assertTrue(CycleConcepts.isCycle("Deux-Roues"))
        assertTrue(CycleConcepts.isCycle("deux roues"))
        assertTrue(CycleConcepts.isCycle("2 roues"))
    }

    @Test
    fun `pluriels reconnus`() {
        assertTrue(CycleConcepts.isCycle("bicycles"))
        assertTrue(CycleConcepts.isCycle("Bikes"))
        assertTrue(CycleConcepts.isCycle("vélos"))
        assertTrue(CycleConcepts.isCycle("scooters"))
        assertTrue(CycleConcepts.isCycle("electric scooters"))
        assertTrue(CycleConcepts.isCycle("trottinettes"))
        assertTrue(CycleConcepts.isCycle("two wheelers"))
        assertTrue(CycleConcepts.isCycle("kick scooters"))
        assertTrue(CycleConcepts.isCycle("electric bikes"))
        assertTrue(CycleConcepts.isCycle("bicyclettes"))
        assertTrue(CycleConcepts.isCycle("escooters"))
    }

    @Test
    fun `noms parles`() {
        assertEquals("vélo", CycleConcepts.nameOf("bicycle")!!.fr)
        assertEquals("bicycle", CycleConcepts.nameOf("vélo")!!.en)
        assertEquals("arrêté", CycleConcepts.nameOf("bike")!!.stoppedFr)
        assertEquals("trottinette", CycleConcepts.nameOf("scooter")!!.fr)
        assertEquals("scooter", CycleConcepts.nameOf("trottinette")!!.en)
        assertEquals("arrêtée", CycleConcepts.nameOf("electric scooter")!!.stoppedFr)
        assertEquals("vélo", CycleConcepts.nameOf("bicyclette")!!.fr)
        assertEquals("trottinette", CycleConcepts.nameOf("kick scooter")!!.fr)
        assertEquals("deux-roues", CycleConcepts.nameOf("two-wheeler")!!.fr)
        assertEquals("two-wheeler", CycleConcepts.nameOf("2-roues")!!.en)
        assertEquals("arrêté", CycleConcepts.nameOf("deux roues")!!.stoppedFr)
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
