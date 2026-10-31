package dev.sam3vision.core.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Lieux connus ([KnownPlaces.resolve]) : nom usuel → adresse postale.
 *
 * Contrat : l'égalité est stricte mais insensible à la casse, aux accents,
 * aux traits d'union et à l'article initial (« the », « le », « la »…) ;
 * tout le reste (lieu inconnu, quartier seul, nom dans une phrase) passe
 * inchangé, sans jamais lever.
 */
class KnownPlacesTest {

    @Test
    fun `sacre-coeur dicte en anglais part vers son adresse`() {
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("the Sacré Coeur"))
    }

    @Test
    fun `sacre-coeur tolere articles casses accents et traits d'union`() {
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("le Sacré-Cœur"))
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("SACRE COEUR"))
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("La Basilique du Sacré-Cœur"))
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("Sacré-Cœur, Montmartre"))
        assertEquals(KnownPlaces.SACRE_COEUR_ADDRESS, KnownPlaces.resolve("Basilica of the Sacred Heart"))
    }

    @Test
    fun `lieu inconnu passe inchange`() {
        assertEquals("la gare", KnownPlaces.resolve("la gare"))
        assertEquals("Montmartre", KnownPlaces.resolve("Montmartre"))
        assertEquals("restaurant près du Sacré-Cœur", KnownPlaces.resolve("restaurant près du Sacré-Cœur"))
    }

    @Test
    fun `blanc passe inchange sans lever`() {
        assertEquals("", KnownPlaces.resolve(""))
        assertEquals("   ", KnownPlaces.resolve("   "))
    }
}
