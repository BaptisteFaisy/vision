package dev.sam3vision.core.live

import dev.sam3vision.core.crossing.CrossingPrompts
import dev.sam3vision.core.traverse.VehicleConcepts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePromptsTest {

    @Test
    fun `seize concepts exterieur par defaut, plafond client a vingt deux`() {
        assertEquals(16, LivePrompts.default.size)
        assertEquals(
            listOf(
                "person", "car", "pedestrian traffic light", "curb", "sidewalk",
                "crosswalk", "pole", "stairs", "fence", "wall", "barrier", "sign",
                "street lamp", "animal", "trash can", "souvenir shop",
            ),
            LivePrompts.default,
        )
        assertEquals(22, LivePrompts.MAX)
        assertEquals(LivePrompts.default, LivePrompts.normalize(LivePrompts.default))
        assertEquals("person", LivePrompts.default.first())
    }

    @Test
    fun `murs clotures barrieres suivis en exterieur, portail et cles hors defaut`() {
        // Spec v4 : grillages, murs et barrières au même rang dehors ;
        // portail et clés sortis (clés = dedans).
        assertFalse("keys" in LivePrompts.default)
        assertTrue("barrier" in LivePrompts.default)
        assertTrue("fence" in LivePrompts.default)
        assertTrue("fence" in LivePrompts.outdoor)
        assertFalse("gate" in LivePrompts.default)
        assertTrue("wall" in LivePrompts.default)
        assertFalse("door" in LivePrompts.default)
        assertFalse("window" in LivePrompts.default)
    }

    @Test
    fun `foule et vehicules sortis du defaut, souvenirs restes`() {
        // Spec v4 : la foule (poussette, enfant, chien), les véhicules hors
        // voiture et la trottinette sortent de la détection dehors ; la
        // boutique de souvenirs reste.
        assertFalse("stroller" in LivePrompts.default)
        assertFalse("child" in LivePrompts.default)
        assertFalse("dog" in LivePrompts.default)
        assertFalse("electric scooter" in LivePrompts.default)
        assertFalse("bus" in LivePrompts.default)
        assertFalse("bicycle" in LivePrompts.default)
        assertFalse("motorcycle" in LivePrompts.default)
        assertTrue("sidewalk" in LivePrompts.default)
        assertTrue("fence" in LivePrompts.default)
        assertTrue("souvenir shop" in LivePrompts.default)
        assertFalse("beanie" in LivePrompts.default)
        assertFalse("socks" in LivePrompts.default)
        assertFalse("keys" in LivePrompts.default)
        assertFalse("stroller" in LivePrompts.outdoor)
        assertFalse("child" in LivePrompts.outdoor)
        assertFalse("electric scooter" in LivePrompts.outdoor)
        assertFalse("truck" in LivePrompts.default)
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
        // Depuis « vire bus, bicycle … » (ordre user du 01/11) le défaut ne
        // porte plus les cinq véhicules : c'est `VehicleConcepts.promptsFor`
        // qui les rétablit quand la Traversée s'arme, et elle les met en tête.
        val crossing = VehicleConcepts.promptsFor(LivePrompts.default)
        (VehicleConcepts.PROMPTS).forEach { concept ->
            assertTrue(concept, concept in crossing)
        }
        assertEquals(VehicleConcepts.PROMPTS, crossing.take(VehicleConcepts.PROMPTS.size))
        assertTrue("curb" in LivePrompts.default)
    }

    @Test
    fun `preset exterieur replie sur le defaut seize concepts`() {
        assertEquals(LivePrompts.default, LivePrompts.outdoor)
        assertEquals(16, LivePrompts.outdoor.size)
        assertTrue(LivePrompts.outdoor.size <= LivePrompts.MAX)
        assertEquals(LivePrompts.outdoor, LivePrompts.normalize(LivePrompts.outdoor))
    }

    @Test
    fun `trottoir dans le defaut, benne nulle part`() {
        assertTrue("sidewalk" in LivePrompts.outdoor)
        assertFalse("dumpster" in LivePrompts.outdoor)
        assertFalse("dumpster" in LivePrompts.default)
        assertFalse("benne" in LivePrompts.default)
        assertEquals(16, LivePrompts.outdoor.size)
    }

    @Test
    fun `dix objets dedans spec v4`() {
        assertEquals(
            listOf(
                "keys", "medicine box", "glasses", "glasses case", "phone",
                "shoes", "door", "wall", "person", "socks",
            ),
            LivePrompts.indoor,
        )
        assertEquals(10, LivePrompts.indoor.size)
        assertFalse("stroller" in LivePrompts.indoor)
        assertTrue("keys" in LivePrompts.indoor)
        assertEquals(LivePrompts.default, LivePrompts.forMode(true))
        assertEquals(LivePrompts.indoor, LivePrompts.forMode(false))
    }

    @Test
    fun `le mode exterieur iSee suit les defauts seize concepts`() {
        val exterior = LivePrompts.forMode(true)
        assertEquals(16, exterior.size)
        assertTrue("wall" in exterior)
        assertFalse("gate" in exterior)
        assertTrue("fence" in exterior)
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
    fun `tous les prompts tiennent en vingt deux concepts de trente deux caracteres au plus`() {
        listOf(LivePrompts.default, LivePrompts.outdoor, LivePrompts.indoor).forEach { prompts ->
            assertTrue(prompts.size <= LivePrompts.MAX)
            assertTrue(prompts.size <= 22)
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
    fun `la liste est bornee a vingt deux prompts`() {
        val many = (1..30).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet22", normalized.last())
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
            LivePrompts.indoor + "wallet",
            LivePrompts.withConcept(LivePrompts.indoor, "wallet"),
        )
    }

    @Test
    fun `find the x sous le plafond s ajoute sans rien chasser`() {
        // La liste par défaut est à 16, le plafond à 22 : « Find the X »
        // ajoute (« wallet ») sans évincer personne.
        val next = LivePrompts.withConcept(LivePrompts.default, "wallet")
        assertEquals(17, next.size)
        assertTrue("wallet" in next)
        assertTrue("souvenir shop" in next)
        assertEquals(LivePrompts.default + "wallet", next)
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
        assertEquals(listOf("wallet"), LivePrompts.followedOf(LivePrompts.withConcept(LivePrompts.indoor, "wallet"), false))
    }
}
