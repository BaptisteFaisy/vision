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

    @Test
    fun `une poussette bloque sans etre une personne`() {
        // Audit foule du 01/11 : le corps qui bloque n'est pas toujours debout.
        // isPerson reste strict — « à la personne » ne doit pas dire
        // « à la poussette ».
        assertTrue(PersonLabels.isBody("poussette"))
        assertTrue(PersonLabels.isBody("stroller"))
        assertTrue(PersonLabels.isBody("wheelchair"))
        assertTrue(PersonLabels.isBody("fauteuil roulant"))
        assertTrue(PersonLabels.isBody("trottinette"))
        assertTrue(PersonLabels.isBody("electric scooter"))
        assertFalse(PersonLabels.isPerson("poussette"))
        assertFalse(PersonLabels.isPerson("stroller"))
        assertFalse(PersonLabels.isPerson("wheelchair"))
        assertFalse(PersonLabels.isPerson("trottinette"))
    }

    @Test
    fun `une personne est un corps, casse et pluriel neutralises`() {
        assertTrue(PersonLabels.isBody("personne"))
        assertTrue(PersonLabels.isBody("Personne"))
        assertTrue(PersonLabels.isBody("person"))
        assertTrue(PersonLabels.isBody("piéton"))
        assertTrue(PersonLabels.isBody("poussettes"))
        assertTrue(PersonLabels.isBody("strollers"))
        assertFalse(PersonLabels.isBody("car"))
        assertFalse(PersonLabels.isBody("socks"))
        assertFalse(PersonLabels.isBody(""))
    }
}
