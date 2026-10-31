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
        listOf("stairs", "sidewalk", "crosswalk", "pole", "curb", "pedestrian traffic light", "pedestrian signal head", "souvenir shop", "beanie", "socks").forEach { concept ->
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

    @Test
    fun `postures assises et roulees ont leur hauteur`() {
        assertEquals(1.25f, KnownSizes.heightMeters("personne assise")!!, 0.001f)
        assertEquals(1.25f, KnownSizes.heightMeters("seated person")!!, 0.001f)
        assertEquals(1.25f, KnownSizes.heightMeters("sitting person")!!, 0.001f)
        assertEquals(1.28f, KnownSizes.heightMeters("homme assis")!!, 0.001f)
        assertEquals(1.23f, KnownSizes.heightMeters("femme assise")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("cycliste")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("cyclist")!!, 0.001f)
        assertEquals(1.25f, KnownSizes.heightMeters("fauteuil roulant")!!, 0.001f)
        assertEquals(1.25f, KnownSizes.heightMeters("wheelchair")!!, 0.001f)
        // « personne » seule reste debout : l'entrée exacte prime sur le premier mot
        assertEquals(1.70f, KnownSizes.heightMeters("personne")!!, 0.001f)
    }

    @Test
    fun `sous types de vehicules calibres`() {
        assertEquals(1.65f, KnownSizes.heightMeters("suv")!!, 0.001f)
        assertEquals(1.95f, KnownSizes.heightMeters("van")!!, 0.001f)
        assertEquals(1.95f, KnownSizes.heightMeters("camionnette")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("pickup")!!, 0.001f)
        assertEquals(1.75f, KnownSizes.heightMeters("pick up")!!, 0.001f)
    }

    @Test
    fun `tolerances par classe, porte sure et arbre flou`() {
        assertEquals(0.06f, KnownSizes.toleranceFraction("personne"), 0.001f)
        assertEquals(0.05f, KnownSizes.toleranceFraction("homme"), 0.001f)
        assertEquals(0.03f, KnownSizes.toleranceFraction("porte"), 0.001f)
        assertEquals(0.30f, KnownSizes.toleranceFraction("arbre"), 0.001f)
        assertEquals(0.08f, KnownSizes.toleranceFraction("personne assise"), 0.001f)
        assertEquals(KnownSizes.DEFAULT_TOLERANCE, KnownSizes.toleranceFraction("licorne"), 0.001f)
        assertEquals(KnownSizes.DEFAULT_TOLERANCE, KnownSizes.toleranceFraction(""), 0.001f)
        // pluriel et premier mot suivent la hauteur
        assertEquals(0.06f, KnownSizes.toleranceFraction("personnes"), 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("clôture grillagée"), 0.001f)
    }

    @Test
    fun `poids de fusion decroit avec la tolerance`() {
        val door = KnownSizes.fusionWeight("porte")
        val person = KnownSizes.fusionWeight("personne")
        val tree = KnownSizes.fusionWeight("arbre")
        assertTrue(door > person)
        assertTrue(person > tree)
        assertEquals(1f / 1.03f, door, 0.001f)
        assertTrue(tree > 0f)
    }

    @Test
    fun `lampadaire et street lamp partagent 5 m`() {
        assertEquals(5.00f, KnownSizes.heightMeters("lampadaire")!!, 0.001f)
        assertEquals(5.00f, KnownSizes.heightMeters("street lamp")!!, 0.001f)
        assertEquals(5.00f, KnownSizes.heightMeters("Street Lamp")!!, 0.001f)
    }

    @Test
    fun `panneau de signalisation calibre comme panneau`() {
        assertEquals(2.20f, KnownSizes.heightMeters("traffic sign")!!, 0.001f)
        assertEquals(2.20f, KnownSizes.heightMeters("Traffic Sign")!!, 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("traffic sign"), 0.001f)
    }

    @Test
    fun `dernier mot anglais en repli, premier mot francais d'abord`() {
        assertEquals(2.20f, KnownSizes.heightMeters("a traffic sign")!!, 0.001f)
        assertEquals(5.00f, KnownSizes.heightMeters("vieux lampadaire")!!, 0.001f)
        // le français garde la priorité : « sac » avant « dos »
        assertEquals(0.40f, KnownSizes.heightMeters("sac à dos")!!, 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("a traffic sign"), 0.001f)
    }

    @Test
    fun `pole reste profondeur meme avec street lamp calibre`() {
        assertNull(KnownSizes.heightMeters("pole"))
        assertTrue("pole" in KnownSizes.DEPTH_ONLY)
        assertEquals(5.00f, KnownSizes.heightMeters("street lamp")!!, 0.001f)
    }
}
