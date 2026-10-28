package dev.sam3vision.core.distance

import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Table des hauteurs réelles : tout ce que le direct suit par défaut doit être calibré. */
class KnownSizesTest {

    @Test
    fun `concepts par defaut du direct sont tous connus`() {
        LivePrompts.default.forEach { concept ->
            assertNotNull("concept non calibré : $concept", KnownSizes.heightMeters(concept))
        }
    }

    @Test
    fun `synonymes francais et anglais donnent la meme hauteur`() {
        assertEquals(1.70f, KnownSizes.heightMeters("person")!!, 0.001f)
        assertEquals(1.70f, KnownSizes.heightMeters("Personne")!!, 0.001f)
        assertEquals(1.70f, KnownSizes.heightMeters("  personnes ")!!, 0.001f)
        assertEquals(1.45f, KnownSizes.heightMeters("VOITURE")!!, 0.001f)
        assertEquals(1.45f, KnownSizes.heightMeters("car")!!, 0.001f)
    }

    @Test
    fun `accents et ponctuation sont neutralises`() {
        assertEquals("telephone", KnownSizes.canonical("Téléphone"))
        assertEquals("canape", KnownSizes.canonical("Canapé."))
        assertEquals("bouteille", KnownSizes.canonical("  bouteille d'eau  ").substringBefore(' '))
        assertEquals(0.15f, KnownSizes.heightMeters("Téléphone")!!, 0.001f)
    }

    @Test
    fun `premier mot connu suffit pour une expression`() {
        assertEquals(0.40f, KnownSizes.heightMeters("sac à dos")!!, 0.001f)
    }

    @Test
    fun `concept inconnu reste inconnu`() {
        assertNull(KnownSizes.heightMeters("licorne"))
        assertNull(KnownSizes.heightMeters(""))
        assertNull(KnownSizes.heightMeters("   "))
    }
}