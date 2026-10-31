package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.traverse.VehicleConcepts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePromptsTest {

    @Test
    fun `dix sept concepts exterieur par defaut, au plafond client`() {
        assertEquals(17, LivePrompts.default.size)
        assertEquals(
            listOf(
                "person", "car", "bus", "bicycle", "pedestrian traffic light",
                "pedestrian signal head", "curb", "sidewalk", "crosswalk", "pole",
                "stairs", "barrier", "fence", "gate", "souvenir shop", "beanie",
                "socks",
            ),
            LivePrompts.default,
        )
        assertEquals(17, LivePrompts.MAX)
        assertEquals(LivePrompts.default, LivePrompts.normalize(LivePrompts.default))
    }

    @Test
    fun `barrieres clotures et portails suivis en exterieur, porte mur et fenetre sortis`() {
        assertTrue("barrier" in LivePrompts.default)
        assertTrue("fence" in LivePrompts.default)
        assertTrue("gate" in LivePrompts.default)
        // Unification traversée/normal (30/10) : porte, mur et fenêtre cèdent
        // leurs slots aux véhicules, au feu et à la bordure.
        assertFalse("door" in LivePrompts.default)
        assertFalse("wall" in LivePrompts.default)
        assertFalse("window" in LivePrompts.default)
    }

    @Test
    fun `souvenirs suivis par defaut, camion moto et trottinette sortis`() {
        // Ordre user du 31/10 (boutique du square) : les trois slots
        // camion/moto/trottinette passent aux souvenirs, des deux côtés
        // (direct et véhicules traversée).
        assertTrue("souvenir shop" in LivePrompts.default)
        assertTrue("beanie" in LivePrompts.default)
        assertTrue("socks" in LivePrompts.default)
        assertFalse("truck" in LivePrompts.default)
        assertFalse("motorcycle" in LivePrompts.default)
        assertFalse("electric scooter" in LivePrompts.default)
        assertFalse("scooter" in LivePrompts.default)
    }

    @Test
    fun `la traversee est couverte par les defauts, memes masques`() {
        // Mêmes masques (reel audit 31/10) : la Traversée voit les défauts,
        // plus les extras camion/moto en mode actif (voir PROMPTS) ; les
        // variantes du feu (LIGHTS) couvrent les mêmes masques que LIGHT.
        val extras = setOf("truck", "motorcycle")
        assertTrue(CrossingPrompts.PROMPTS.containsAll(extras))
        CrossingPrompts.PROMPTS.forEach { concept ->
            val covered = concept in extras || concept in LivePrompts.default ||
                (CrossingPrompts.isLightLabel(concept) && CrossingPrompts.LIGHT in LivePrompts.default)
            assertTrue(concept, covered)
        }
        (VehicleConcepts.PROMPTS - extras - "scooter").forEach { concept ->
            assertTrue(concept, concept in LivePrompts.default)
        }
        assertTrue("curb" in LivePrompts.default)
    }

    @Test
    fun `preset exterieur quinze concepts de cheminement sous le plafond`() {
        assertEquals(
            listOf(
                "person", "pole", "street lamp", "bollard", "fence", "barrier", "gate",
                "sign", "tree", "trash can", "sidewalk", "crosswalk", "animal",
                "pedestrian traffic light", "pedestrian signal head",
            ),
            LivePrompts.outdoor,
        )
        assertTrue(LivePrompts.outdoor.size <= LivePrompts.MAX)
        assertEquals(LivePrompts.outdoor, LivePrompts.normalize(LivePrompts.outdoor))
    }

    @Test
    fun `trottoir remplace la benne la moins utile dans le preset exterieur`() {
        assertTrue("sidewalk" in LivePrompts.outdoor)
        assertFalse("dumpster" in LivePrompts.outdoor)
        assertTrue("pedestrian signal head" in LivePrompts.outdoor)
        assertEquals(15, LivePrompts.outdoor.size)
    }

    @Test
    fun `huit objets d interieur dedans, douze dangers dehors`() {
        assertEquals(
            listOf("person", "door", "chair", "table", "stairs", "wall", "window", "crosswalk"),
            LivePrompts.indoor,
        )
        assertEquals(8, LivePrompts.indoor.size)
        assertEquals(LivePrompts.default, LivePrompts.forMode(true))
        assertEquals(LivePrompts.indoor, LivePrompts.forMode(false))
    }

    @Test
    fun `le mode exterieur iSee suit les defauts avec clotures et portails`() {
        val exterior = LivePrompts.forMode(true)
        assertEquals(17, exterior.size)
        assertTrue("fence" in exterior)
        assertTrue("gate" in exterior)
        assertTrue("sidewalk" in exterior)
    }

    @Test
    fun `les deux mondes tiennent sous le plafond et se normalisent a l identique`() {
        assertTrue(LivePrompts.default.size <= LivePrompts.MAX)
        assertTrue(LivePrompts.indoor.size <= LivePrompts.MAX)
        assertTrue(LivePrompts.outdoor.size <= LivePrompts.MAX)
        assertEquals(LivePrompts.indoor, LivePrompts.normalize(LivePrompts.indoor))
        LivePrompts.indoor.forEach { concept ->
            assertTrue(concept.length <= LivePrompts.MAX_LENGTH)
        }
    }

    @Test
    fun `tous les prompts tiennent en dix sept concepts de trente deux caracteres au plus`() {
        listOf(LivePrompts.default, LivePrompts.outdoor, LivePrompts.indoor).forEach { prompts ->
            assertTrue(prompts.size <= LivePrompts.MAX)
            assertTrue(prompts.size <= 17)
            prompts.forEach { concept ->
                assertTrue(concept.length <= LivePrompts.MAX_LENGTH)
                assertTrue(concept.length <= 32)
            }
        }
    }

    @Test
    fun `une liste vide retombe sur les defauts`() {
        assertEquals(LivePrompts.default, LivePrompts.normalize(emptyList()))
    }

    @Test
    fun `une liste uniquement blanche retombe sur les defauts`() {
        assertEquals(LivePrompts.default, LivePrompts.normalize(listOf("", "   ", "\t")))
    }

    @Test
    fun `les espaces sont retires et les doublons elimines`() {
        assertEquals(listOf("person", "car"), LivePrompts.normalize(listOf(" person ", "car", "person")))
    }

    @Test
    fun `la liste est bornee a dix sept prompts`() {
        val many = (1..20).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet17", normalized.last())
    }

    @Test
    fun `un prompt trop long est rejete`() {
        val tooLong = "x".repeat(LivePrompts.MAX_LENGTH + 1)
        assertEquals(listOf("cat"), LivePrompts.normalize(listOf(tooLong, "cat")))
        assertTrue(LivePrompts.normalize(listOf(tooLong)).isNotEmpty())
    }

    @Test
    fun `une liste entierement rejetee retombe sur les defauts`() {
        val tooLong = "y".repeat(LivePrompts.MAX_LENGTH + 1)
        assertEquals(LivePrompts.default, LivePrompts.normalize(listOf(tooLong)))
    }

    @Test
    fun `un prompt a la longueur maximale est accepte`() {
        val atLimit = "z".repeat(LivePrompts.MAX_LENGTH)
        assertEquals(listOf(atLimit), LivePrompts.normalize(listOf(atLimit)))
    }

    @Test
    fun `le champ suit accepte virgules points virgules et retours ligne`() {
        assertEquals(listOf("a", "b", "c"), LivePrompts.field(" a , b;c "))
        assertEquals(listOf("x", "y"), LivePrompts.field("x\ny"))
        assertEquals(LivePrompts.default, LivePrompts.field(" , ;\n"))
    }
}
