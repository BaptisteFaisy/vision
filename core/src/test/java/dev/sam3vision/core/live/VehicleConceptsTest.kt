package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portée de la voix, mentions informatives : à 4 m ou moins tout se dit
 * (inchangé), au-delà et sous 10 m seuls les véhicules (voitures, vélos,
 * trottinettes électriques) se disent, à 10 m et plus silence total.
 * Distance inconnue : dit (prudence, inchangé — comme `Guidance`).
 */
class VehicleConceptsTest {

    private fun placed(label: String, meters: Float?) = PlacedLabel(label, 0.5f, 0.5f, meters)

    @Test
    fun `velo voiture et scooter se disent entre 4 et 10 m`() {
        listOf("car", "bicycle", "electric scooter").forEach { label ->
            listOf(4.1f, 6f, 9f, 9.9f).forEach { meters ->
                assertTrue("$label à $meters", VehicleConcepts.audible(placed(label, meters)))
            }
        }
    }

    @Test
    fun `vehicule reconnu en francais casse et pluriel`() {
        listOf("Car", "BICYCLE", "Electric Scooter", "voiture", "Voitures", "vélo", "VELOS").forEach { label ->
            assertTrue(label, VehicleConcepts.isVehicle(label))
            assertTrue(label, VehicleConcepts.audible(placed(label, 7f)))
        }
    }

    @Test
    fun `poteau personne et mobilier tus au-dela de 4 m`() {
        listOf("person", "personne", "pole", "poteau", "barrier", "tree", "stairs", "sidewalk").forEach { label ->
            assertFalse(label, VehicleConcepts.isVehicle(label))
            listOf(4.1f, 6f, 9.9f).forEach { meters ->
                assertFalse("$label à $meters", VehicleConcepts.audible(placed(label, meters)))
            }
        }
    }

    @Test
    fun `a 4 m ou moins tout se dit`() {
        listOf("car", "bicycle", "person", "personne", "pole", "poteau", "barrier").forEach { label ->
            listOf(0.5f, 2f, 4f).forEach { meters ->
                assertTrue("$label à $meters", VehicleConcepts.audible(placed(label, meters)))
            }
        }
    }

    @Test
    fun `a 10 m et plus silence total meme les vehicules`() {
        listOf("car", "bicycle", "electric scooter", "person", "pole").forEach { label ->
            listOf(10f, 12f, 50f).forEach { meters ->
                assertFalse("$label à $meters", VehicleConcepts.audible(placed(label, meters)))
            }
        }
    }

    @Test
    fun `distance inconnue dite par prudence`() {
        listOf("car", "person", "pole").forEach { label ->
            assertTrue("$label sans mesure", VehicleConcepts.audible(placed(label, null)))
            assertTrue("$label NaN", VehicleConcepts.audible(placed(label, Float.NaN)))
        }
    }

    @Test
    fun `audible garde les mentions et tait le reste`() {
        val labels = listOf(
            placed("car", 7f),
            placed("personne", 6f),
            placed("pole", 2f),
            placed("bicycle", 11f),
        )

        assertEquals(
            listOf(placed("car", 7f), placed("pole", 2f)),
            VehicleConcepts.audible(labels),
        )
    }
}
