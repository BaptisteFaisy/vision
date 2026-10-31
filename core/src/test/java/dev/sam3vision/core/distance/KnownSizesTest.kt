package dev.sam3vision.core.distance

import dev.sam3vision.core.live.LivePrompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table des hauteurs réelles : chaque défaut est calibré ou explicitement profondeur. */
class KnownSizesTest {

    @Test
    fun `concepts par defaut calibres ou profondeur documentee`() {
        LivePrompts.default.forEach { concept ->
            val ok = KnownSizes.heightMeters(concept) != null || concept in KnownSizes.DEPTH_ONLY
            assertTrue("concept ni calibré ni profondeur : $concept", ok)
        }
    }

    @Test
    fun `defauts profondeur sans hauteur stenope`() {
        listOf("stairs", "sidewalk", "crosswalk", "pole", "curb").forEach { concept ->
            assertNull(KnownSizes.heightMeters(concept))
            assertTrue(concept in KnownSizes.DEPTH_ONLY)
        }
    }

    @Test
    fun `famille bordure sans hauteur stenope, distance par profondeur`() {
        listOf("kerb", "step", "steps", "bordure", "rebord", "curb ramp", "bateau").forEach { concept ->
            assertNull(KnownSizes.heightMeters(concept))
            assertTrue(concept in KnownSizes.DEPTH_ONLY)
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
    fun `les vehicules suivis sont calibres en francais et en anglais`() {
        assertEquals(1.10f, KnownSizes.heightMeters("bicycle")!!, 0.001f)
        assertEquals(1.10f, KnownSizes.heightMeters("Vélo")!!, 0.001f)
        assertEquals(1.45f, KnownSizes.heightMeters("car")!!, 0.001f)
        assertEquals(1.45f, KnownSizes.heightMeters("voiture")!!, 0.001f)
        assertEquals(2.80f, KnownSizes.heightMeters("truck")!!, 0.001f)
        assertEquals(2.80f, KnownSizes.heightMeters("camion")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("electric scooter")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("Trottinette électrique")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("scooter")!!, 0.001f)
    }

    @Test
    fun `portes et barrieres calibre en francais et en anglais`() {
        assertEquals(2.05f, KnownSizes.heightMeters("door")!!, 0.001f)
        assertEquals(2.05f, KnownSizes.heightMeters("Porte")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("barrier")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("Barrière")!!, 0.001f)
        assertEquals(1.00f, KnownSizes.heightMeters("barrières")!!, 0.001f)
    }

    @Test
    fun `exterieur sept concepts calibres et poteau en profondeur`() {
        listOf("person", "bollard", "fence", "barrier", "gate", "sign", "tree").forEach { concept ->
            assertNotNull("concept extérieur non calibré : $concept", KnownSizes.heightMeters(concept))
        }
        assertNull(KnownSizes.heightMeters("pole"))
        assertTrue("pole" in KnownSizes.DEPTH_ONLY)
    }

    @Test
    fun `synonymes exterieur donnent la hauteur du concept`() {
        assertEquals(0.80f, KnownSizes.heightMeters("bollard")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("Borne")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("plot")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("potelet")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("fence")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("cloture")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("grillage")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("gate")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("portail")!!, 0.001f)
        assertEquals(2.20f, KnownSizes.heightMeters("panneau")!!, 0.001f)
        assertEquals(4.00f, KnownSizes.heightMeters("arbre")!!, 0.001f)
    }

    @Test
    fun `accents pluriels et expressions exterieur`() {
        assertEquals(1.50f, KnownSizes.heightMeters("Clôtures")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("grillages")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("Portails")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("bornes")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("potelets")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("plots")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("clôture grillagée")!!, 0.001f)
        assertEquals(0.80f, KnownSizes.heightMeters("borne anti-stationnement")!!, 0.001f)
    }

    @Test
    fun `concept inconnu reste inconnu`() {
        assertNull(KnownSizes.heightMeters("licorne"))
        assertNull(KnownSizes.heightMeters(""))
        assertNull(KnownSizes.heightMeters("   "))
    }
}