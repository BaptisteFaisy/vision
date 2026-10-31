package dev.sam3vision.core.traverse

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Politique des seuils d'expansion par label (PISTE2) : les véhicules lus par
 * [VehicleConcepts] prennent les seuils véhicules, tout le reste garde les
 * seuils personnes. JUnit seul.
 */
class ApproachLabelsTest {

    @Test
    fun `vehicule reconnu - seuils vehicules`() {
        assertEquals(VEHICLE_APPROACH_THRESHOLDS, approachThresholdsFor("car"))
        assertEquals(VEHICLE_APPROACH_THRESHOLDS, approachThresholdsFor("Cars"))
        assertEquals(VEHICLE_APPROACH_THRESHOLDS, approachThresholdsFor("voiture"))
        assertEquals(VEHICLE_APPROACH_THRESHOLDS, approachThresholdsFor("bus"))
    }

    @Test
    fun `personne et inconnu - seuils personnes`() {
        assertEquals(ApproachThresholds(), approachThresholdsFor("person"))
        assertEquals(ApproachThresholds(), approachThresholdsFor("personne"))
        assertEquals(ApproachThresholds(), approachThresholdsFor("poteau"))
    }

    @Test
    fun `surcharge - seuils personnalises par type`() {
        val custom = ApproachThresholds(minGrowthPerSec = 0.05f)
        assertEquals(custom, approachThresholdsFor("car", vehicle = custom))
        assertEquals(custom, approachThresholdsFor("person", person = custom))
    }
}
