package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.traverse.VehicleConcepts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePromptsTest {

    @Test
    fun `dix huit concepts exterieur par defaut, au plafond client`() {
        assertEquals(18, LivePrompts.default.size)
        assertEquals(
            listOf(
                "person", "car", "bus", "bicycle", "pedestrian traffic light",
                "pedestrian signal head", "curb", "sidewalk", "crosswalk", "pole",
                "stairs", "keys", "fence", "gate", "souvenir shop", "beanie",
                "socks", "wall",
            ),
            LivePrompts.default,
        )
        assertEquals(18, LivePrompts.MAX)
        assertEquals(LivePrompts.default, LivePrompts.normalize(LivePrompts.default))
        assertEquals("person", LivePrompts.default.first())
    }

    @Test
    fun `murs clotures et portails suivis en exterieur, barrier et keys hors defaut`() {
        // Ordre user du 31/10 : « fence » garde son slot, « barrier » sort
        // (reconnu encore par le preset extérieur), « wall » entre en 18e.
        // Ordre user du 01/11 : les souvenirs et les clés **restent** dans le
        // défaut (boutique du square) — la foule passe par le preset extérieur.
        assertTrue("keys" in LivePrompts.default)
        assertFalse("barrier" in LivePrompts.default)
        assertTrue("fence" in LivePrompts.default)
        assertTrue("gate" in LivePrompts.default)
        assertTrue("wall" in LivePrompts.default)
        // Unification traversée/normal (30/10) : porte et fenêtre cèdent
        // leurs slots aux véhicules, au feu et à la bordure.
        assertFalse("door" in LivePrompts.default)
        assertFalse("window" in LivePrompts.default)
    }

    @Test
    fun `foule suivie par le preset exterieur, souvenirs restes dans le defaut`() {
        // Ordre user du 01/11 : « remets souvenir shop, beanie, socks, keys ».
        // Le défaut garde donc ses 18 concepts de boutique et de rue ; la
        // poussette, l'enfant et la trottinette passent dans le preset
        // extérieur (18 aussi), qui est le mode de marche urbaine.
        assertFalse("stroller" in LivePrompts.default)
        assertFalse("child" in LivePrompts.default)
        assertFalse("electric scooter" in LivePrompts.default)
        assertFalse("dog" in LivePrompts.default)
        assertTrue("souvenir shop" in LivePrompts.default)
        assertTrue("beanie" in LivePrompts.default)
        assertTrue("socks" in LivePrompts.default)
        assertTrue("keys" in LivePrompts.default)
        assertTrue("stroller" in LivePrompts.outdoor)
        assertTrue("child" in LivePrompts.outdoor)
        assertTrue("electric scooter" in LivePrompts.outdoor)
        assertFalse("truck" in LivePrompts.default)
        assertFalse("motorcycle" in LivePrompts.default)
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
                "stroller", "child", "electric scooter",
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
        // 15 de cheminement + poussette, enfant, trottinette (ordre user du
        // 01/11 : la foule se suit dans le preset extérieur, pas dans le défaut).
        assertEquals(18, LivePrompts.outdoor.size)
    }

    @Test
    fun `neuf objets d interieur dedans, poussette incluse, dix huit dangers dehors`() {
        assertEquals(
            listOf(
                "person", "stroller", "door", "chair", "table", "stairs", "wall",
                "window", "crosswalk",
            ),
            LivePrompts.indoor,
        )
        assertEquals(9, LivePrompts.indoor.size)
        assertTrue("stroller" in LivePrompts.indoor)
        assertEquals(LivePrompts.default, LivePrompts.forMode(true))
        assertEquals(LivePrompts.indoor, LivePrompts.forMode(false))
    }

    @Test
    fun `le mode exterieur iSee suit les defauts avec murs clotures et portails`() {
        val exterior = LivePrompts.forMode(true)
        assertEquals(18, exterior.size)
        assertTrue("wall" in exterior)
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
    fun `tous les prompts tiennent en dix huit concepts de trente deux caracteres au plus`() {
        listOf(LivePrompts.default, LivePrompts.outdoor, LivePrompts.indoor).forEach { prompts ->
            assertTrue(prompts.size <= LivePrompts.MAX)
            assertTrue(prompts.size <= 18)
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
    fun `la liste est bornee a dix huit prompts`() {
        val many = (1..20).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet18", normalized.last())
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

    @Test
    fun `find the x rejoint la liste sous le plafond`() {
        assertEquals(
            LivePrompts.indoor + "keys",
            LivePrompts.withConcept(LivePrompts.indoor, "keys"),
        )
    }

    @Test
    fun `find the x au plafond, le dernier concept cede son slot`() {
        // « Find the X » au plafond ne reste plus muet — démontré avec « wallet »
        // : « dog », 18e depuis l'audit foule du 01/11, sort, 18 suivis.
        val next = LivePrompts.withConcept(LivePrompts.default, "wallet")
        assertEquals(LivePrompts.MAX, next.size)
        assertTrue("wallet" in next)
        assertFalse("dog" in next)
        assertEquals(LivePrompts.default.dropLast(1) + "wallet", next)
    }

    @Test
    fun `find the x deja suivi present ou invalide, liste inchangee`() {
        assertEquals(LivePrompts.default, LivePrompts.withConcept(LivePrompts.default, "Person"))
        assertEquals(LivePrompts.indoor, LivePrompts.withConcept(LivePrompts.indoor, "  "))
        assertEquals(LivePrompts.indoor, LivePrompts.withConcept(LivePrompts.indoor, "x".repeat(33)))
    }

    @Test
    fun `suivis explicites dehors hors liste du monde`() {
        assertEquals(emptyList<String>(), LivePrompts.followedOf(LivePrompts.default, true))
        assertEquals(listOf("wallet"), LivePrompts.followedOf(LivePrompts.withConcept(LivePrompts.default, "wallet"), true))
        assertEquals(emptyList<String>(), LivePrompts.followedOf(LivePrompts.withConcept(LivePrompts.default, "Person"), true))
    }

    @Test
    fun `suivis explicites dedans hors liste du monde`() {
        assertEquals(emptyList<String>(), LivePrompts.followedOf(LivePrompts.indoor, false))
        assertEquals(listOf("keys"), LivePrompts.followedOf(LivePrompts.withConcept(LivePrompts.indoor, "keys"), false))
    }
}
