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
        listOf("stairs", "sidewalk", "crosswalk", "pole", "curb", "pedestrian traffic light", "pedestrian signal head", "souvenir shop", "beanie", "socks", "keys").forEach { concept ->
            assertNull(KnownSizes.heightMeters(concept))
            assertTrue(concept in KnownSizes.DEPTH_ONLY)
        }
    }

    @Test
    fun `famille bordure sans hauteur stenope, distance par profondeur`() {
        listOf("kerb", "step", "steps", "bordure", "rebord").forEach { concept ->
            assertNull(KnownSizes.heightMeters(concept))
            assertTrue(concept in KnownSizes.DEPTH_ONLY)
        }
        // Bateau sorti v4 : inconnu, ni hauteur ni profondeur documentée.
        assertNull(KnownSizes.heightMeters("curb ramp"))
        assertNull(KnownSizes.heightMeters("bateau"))
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
    fun `la voiture suivie est calibree, autres vehicules sortis`() {
        assertEquals(1.45f, KnownSizes.heightMeters("car")!!, 0.001f)
        assertEquals(1.45f, KnownSizes.heightMeters("voiture")!!, 0.001f)
        // Spec v4 : vélo, camion et trottinette ne sont plus détectés.
        assertNull(KnownSizes.heightMeters("bicycle"))
        assertNull(KnownSizes.heightMeters("Vélo"))
        assertNull(KnownSizes.heightMeters("truck"))
        assertNull(KnownSizes.heightMeters("camion"))
        assertNull(KnownSizes.heightMeters("electric scooter"))
        assertNull(KnownSizes.heightMeters("Trottinette électrique"))
        assertNull(KnownSizes.heightMeters("scooter"))
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
    fun `exterieur concepts calibres et poteau en profondeur`() {
        listOf("person", "fence", "barrier", "sign").forEach { concept ->
            assertNotNull("concept extérieur non calibré : $concept", KnownSizes.heightMeters(concept))
        }
        // Spec v4 : borne, portail et arbre ne sont plus détectés.
        assertNull(KnownSizes.heightMeters("bollard"))
        assertNull(KnownSizes.heightMeters("gate"))
        assertNull(KnownSizes.heightMeters("tree"))
        assertNull(KnownSizes.heightMeters("pole"))
        assertTrue("pole" in KnownSizes.DEPTH_ONLY)
    }

    @Test
    fun `synonymes exterieur donnent la hauteur du concept`() {
        assertEquals(1.50f, KnownSizes.heightMeters("fence")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("cloture")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("grillage")!!, 0.001f)
        assertEquals(2.20f, KnownSizes.heightMeters("panneau")!!, 0.001f)
        // Spec v4 : borne, portail et arbre ne sont plus détectés.
        assertNull(KnownSizes.heightMeters("bollard"))
        assertNull(KnownSizes.heightMeters("Borne"))
        assertNull(KnownSizes.heightMeters("plot"))
        assertNull(KnownSizes.heightMeters("potelet"))
        assertNull(KnownSizes.heightMeters("gate"))
        assertNull(KnownSizes.heightMeters("portail"))
        assertNull(KnownSizes.heightMeters("arbre"))
    }

    @Test
    fun `accents pluriels et expressions exterieur`() {
        assertEquals(1.50f, KnownSizes.heightMeters("Clôtures")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("grillages")!!, 0.001f)
        assertEquals(1.50f, KnownSizes.heightMeters("clôture grillagée")!!, 0.001f)
        // Spec v4 : portail et borne ne sont plus détectés, même au pluriel.
        assertNull(KnownSizes.heightMeters("Portails"))
        assertNull(KnownSizes.heightMeters("bornes"))
        assertNull(KnownSizes.heightMeters("potelets"))
        assertNull(KnownSizes.heightMeters("plots"))
        assertNull(KnownSizes.heightMeters("borne anti-stationnement"))
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
        // Fauteuil roulant : 0,95 m de dossier (P0 point 7), pas la taille assise
        // d'une personne sur une chaise (1,25 m)
        assertEquals(0.95f, KnownSizes.heightMeters("fauteuil roulant")!!, 0.001f)
        assertEquals(0.95f, KnownSizes.heightMeters("wheelchair")!!, 0.001f)
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
        assertEquals(KnownSizes.DEFAULT_TOLERANCE, KnownSizes.toleranceFraction("arbre"), 0.001f)
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

    @Test
    fun `enfant child et children partagent 1 20 m`() {
        listOf("child", "children", "kid", "enfant", "enfants", "ENFANTS", "Enfant").forEach { label ->
            assertEquals("hauteur $label", 1.20f, KnownSizes.heightMeters(label)!!, 0.001f)
        }
    }

    @Test
    fun `poussette et ses synonymes a 1 05 m`() {
        listOf("poussette", "Poussette", "poussettes", "stroller", "pram", "buggy").forEach { label ->
            assertEquals("hauteur $label", 1.05f, KnownSizes.heightMeters(label)!!, 0.001f)
        }
    }

    @Test
    fun `fauteuil roulant personne assise a 0 95 m`() {
        // canonical() neutralise casse, accents, espaces et ponctuation
        assertEquals("fauteuil roulant", KnownSizes.canonical("  Fauteuil  Roulant. "))
        listOf("wheelchair", "fauteuil roulant", "Fauteuil roulant", "fauteuils roulants").forEach { label ->
            assertEquals("hauteur $label", 0.95f, KnownSizes.heightMeters(label)!!, 0.001f)
        }
        // le repli dernier mot anglais retrouve le fauteuil dans une expression
        assertEquals(0.95f, KnownSizes.heightMeters("electric wheelchair")!!, 0.001f)
    }

    @Test
    fun `trottinette sortie, sans hauteur`() {
        val labels = listOf(
            "trottinette", "Trottinette électrique", "trottinettes électriques",
            "electric scooter", "scooter", "kick scooter",
        )
        labels.forEach { label ->
            assertNull("hauteur $label", KnownSizes.heightMeters(label))
        }
    }

    @Test
    fun `chien et dog a 0 55 m au garrot`() {
        listOf("chien", "chiens", "dog", "dogs", "Chien").forEach { label ->
            assertEquals("hauteur $label", 0.55f, KnownSizes.heightMeters(label)!!, 0.001f)
        }
    }

    @Test
    fun `adulte debout reste 1 70 m et ne couvre pas un enfant`() {
        listOf("person", "personne", "personnes", "humain", "human").forEach { label ->
            assertEquals("hauteur $label", 1.70f, KnownSizes.heightMeters(label)!!, 0.001f)
        }
        // L'adulte est plus haut que l'enfant : deux entrées distinctes, pas une
        // tolérance plus large sur « person » (voir le KDoc de KnownSizes).
        val adult = KnownSizes.heightMeters("person")!!
        val child = KnownSizes.heightMeters("child")!!
        assertTrue("un enfant est lu plus bas qu'un adulte", child < adult)
        // 1,20 / 1,70 : l'écart dépasse largement la tolérance de l'adulte (± 6 %)
        assertEquals(0.71f, child / adult, 0.01f)
        assertTrue(KnownSizes.toleranceFraction("person") < 0.20f)
    }

    @Test
    fun `tolerance par categorie de foule`() {
        assertEquals(0.20f, KnownSizes.toleranceFraction("enfant"), 0.001f)
        assertEquals(0.20f, KnownSizes.toleranceFraction("children"), 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("Poussette"), 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("stroller"), 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("wheelchair"), 0.001f)
        assertEquals(0.25f, KnownSizes.toleranceFraction("fauteuil roulant"), 0.001f)
        assertEquals(KnownSizes.DEFAULT_TOLERANCE, KnownSizes.toleranceFraction("trottinette"), 0.001f)
        assertEquals(KnownSizes.DEFAULT_TOLERANCE, KnownSizes.toleranceFraction("electric scooter"), 0.001f)
        assertEquals(0.30f, KnownSizes.toleranceFraction("chien"), 0.001f)
        assertEquals(0.30f, KnownSizes.toleranceFraction("dogs"), 0.001f)
        // l'adulte garde sa tolérance serrée : la foule n'a pas relâché sa mesure
        assertEquals(0.06f, KnownSizes.toleranceFraction("person"), 0.001f)
    }

    @Test
    fun `poids de fusion de la foule plus prudent que l adulte`() {
        val adult = KnownSizes.fusionWeight("person")
        val child = KnownSizes.fusionWeight("child")
        val stroller = KnownSizes.fusionWeight("poussette")
        val dog = KnownSizes.fusionWeight("chien")
        assertTrue("adulte > enfant", adult > child)
        assertTrue("enfant > poussette", child > stroller)
        assertTrue("poussette > chien", stroller > dog)
        assertEquals(1f / 1.30f, dog, 0.001f)
        assertTrue("un chien pèse encore", dog > 0f)
    }
}
