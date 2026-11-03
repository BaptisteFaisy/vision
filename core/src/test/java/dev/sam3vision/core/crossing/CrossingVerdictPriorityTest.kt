package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Priorité du verdict sur le passage piéton : hors passage le verdict parle
 * à 100 (devant les humains 101 et les bordures 102) ; sur le passage, un
 * verdict changé coupe tout à 99 (front, rouge comme vert — une voiture qui
 * bouge tôle), un verdict maintenu recule à 101 (l'évitement continu des
 * occupants, 100, domine la répétition informative). Pur, testé en JVM.
 */
class CrossingVerdictPriorityTest {

    @Test
    fun `hors passage le verdict parle a 100 change ou pas`() {
        assertEquals(100, CrossingLevels.verdictLevelFor(false, true))
        assertEquals(100, CrossingLevels.verdictLevelFor(false, false))
    }

    @Test
    fun `sur passage un verdict change coupe a 99`() {
        assertEquals(CrossingLevels.ALERT, CrossingLevels.verdictLevelFor(true, true))
        assertEquals(99, CrossingLevels.ALERT)
    }

    @Test
    fun `sur passage un verdict maintenu recule a 101`() {
        assertEquals(CrossingLevels.HUMAN, CrossingLevels.verdictLevelFor(true, false))
    }

    @Test
    fun `echelle sur passage = 99 changement puis 100 occupants puis 101 puis 102`() {
        assertTrue(CrossingLevels.ALERT < CrossingLevels.VERDICT)
        assertTrue(CrossingLevels.VERDICT < CrossingLevels.HUMAN)
        assertTrue(CrossingLevels.HUMAN < CrossingLevels.CURB)
    }
}
