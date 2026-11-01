package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec v4 : listes de concepts caméra verrouillées.
 * Dehors = 16 concepts, dedans = 10 objets, plafond respecté, « Find my X »
 * ajoute (éviction du dernier au plafond), champ « Follow » gardé, bascule
 * indoor-outdoor des listes.
 */
class LivePromptsSpecV4Test {

    @Test
    fun `dehors seize concepts exacts dans l ordre de la spec`() {
        assertEquals(
            listOf(
                "person", "car", "pedestrian traffic light", "curb", "sidewalk", "crosswalk",
                "pole", "stairs", "fence", "wall", "barrier", "sign", "street lamp",
                "animal", "trash can", "souvenir shop",
            ),
            LivePrompts.default,
        )
        assertEquals(16, LivePrompts.default.size)
    }

    @Test
    fun `dedans dix objets exacts dans l ordre de la spec`() {
        assertEquals(
            listOf(
                "keys", "medicine box", "glasses", "glasses case", "phone", "shoes",
                "door", "wall", "person", "socks",
            ),
            LivePrompts.indoor,
        )
        assertEquals(10, LivePrompts.indoor.size)
    }

    @Test
    fun `concepts supprimes absents de la detection dehors`() {
        val dehors = LivePrompts.default
        listOf(
            "bus", "bicycle", "truck", "motorcycle", "keys", "bollard", "tree",
            "gate", "boat", "pedestrian signal head", "electric scooter",
            "stroller", "child", "dog", "beanie",
        ).forEach { concept ->
            assertFalse(concept, concept in dehors)
        }
    }

    @Test
    fun `les deux mondes tiennent sous le plafond et se normalisent a l identique`() {
        assertEquals(22, LivePrompts.MAX)
        assertTrue(LivePrompts.default.size <= LivePrompts.MAX)
        assertTrue(LivePrompts.indoor.size <= LivePrompts.MAX)
        assertTrue(LivePrompts.outdoor.size <= LivePrompts.MAX)
        assertEquals(LivePrompts.default, LivePrompts.normalize(LivePrompts.default))
        assertEquals(LivePrompts.indoor, LivePrompts.normalize(LivePrompts.indoor))
        (LivePrompts.default + LivePrompts.indoor).forEach { concept ->
            assertTrue(concept, concept.length <= LivePrompts.MAX_LENGTH)
        }
    }

    @Test
    fun `normalize borne au plafond et retombe sur les defauts dehors`() {
        val many = (1..30).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet22", normalized.last())
        assertEquals(LivePrompts.default, LivePrompts.normalize(emptyList()))
    }

    @Test
    fun `find my x ajoute sous le plafond sans rien chasser`() {
        val next = LivePrompts.withConcept(LivePrompts.default, "wallet")
        assertEquals(17, next.size)
        assertEquals(LivePrompts.default + "wallet", next)
        val indoorNext = LivePrompts.withConcept(LivePrompts.indoor, "wallet")
        assertEquals(LivePrompts.indoor + "wallet", indoorNext)
    }

    @Test
    fun `find my x au plafond evince le dernier concept`() {
        val full = (1..LivePrompts.MAX).map { "objet$it" }
        val next = LivePrompts.withConcept(full, "wallet")
        assertEquals(LivePrompts.MAX, next.size)
        assertEquals("wallet", next.last())
        assertFalse("objet22" in next)
        assertEquals("objet1", next.first())
    }

    @Test
    fun `find my x deja suivi present ou invalide, liste inchangee`() {
        assertEquals(LivePrompts.default, LivePrompts.withConcept(LivePrompts.default, "Person"))
        assertEquals(LivePrompts.indoor, LivePrompts.withConcept(LivePrompts.indoor, "  "))
        assertEquals(LivePrompts.indoor, LivePrompts.withConcept(LivePrompts.indoor, "x".repeat(33)))
    }

    @Test
    fun `champ follow garde, vide retombe sur les defauts dehors`() {
        assertEquals(listOf("a", "b", "c"), LivePrompts.field(" a , b;c "))
        assertEquals(listOf("x", "y"), LivePrompts.field("x\ny"))
        assertEquals(LivePrompts.default, LivePrompts.field(" , ;\n"))
    }

    @Test
    fun `bascule indoor-outdoor des listes`() {
        assertEquals(LivePrompts.default, LivePrompts.forMode(true))
        assertEquals(LivePrompts.indoor, LivePrompts.forMode(false))
        assertEquals(16, LivePrompts.forMode(true).size)
        assertEquals(10, LivePrompts.forMode(false).size)
    }

    @Test
    fun `suivis explicites hors liste du monde`() {
        assertEquals(emptyList<String>(), LivePrompts.followedOf(LivePrompts.default, true))
        assertEquals(emptyList<String>(), LivePrompts.followedOf(LivePrompts.indoor, false))
        assertEquals(
            listOf("wallet"),
            LivePrompts.followedOf(LivePrompts.withConcept(LivePrompts.default, "wallet"), true),
        )
    }
}
