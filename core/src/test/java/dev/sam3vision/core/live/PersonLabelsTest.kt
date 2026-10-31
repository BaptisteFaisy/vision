package dev.sam3vision.core.live

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vocabulaire personne partagé : ce que la voix et les guetteurs citent. */
class PersonLabelsTest {

    @Test
    fun `les etiquettes anglaises de personne sont reconnues`() {
        assertTrue(PersonLabels.isPerson("person"))
        assertTrue(PersonLabels.isPerson("Person"))
        assertTrue(PersonLabels.isPerson("people"))
        assertTrue(PersonLabels.isPerson("pedestrian"))
        assertTrue(PersonLabels.isPerson("child"))
    }

    @Test
    fun `les etiquettes francaises de personne sont reconnues`() {
        assertTrue(PersonLabels.isPerson("personne"))
        assertTrue(PersonLabels.isPerson("Personnes"))
        assertTrue(PersonLabels.isPerson("piéton"))
        assertTrue(PersonLabels.isPerson("femme"))
        assertTrue(PersonLabels.isPerson("enfant"))
    }

    @Test
    fun `le reste n est pas une personne`() {
        assertFalse(PersonLabels.isPerson("car"))
        assertFalse(PersonLabels.isPerson("crosswalk"))
        assertFalse(PersonLabels.isPerson("traffic light"))
        assertFalse(PersonLabels.isPerson("pedestrian traffic light"))
        assertFalse(PersonLabels.isPerson("feu piéton"))
        assertFalse(PersonLabels.isPerson("personnel"))
        assertFalse(PersonLabels.isPerson(""))
    }
}
