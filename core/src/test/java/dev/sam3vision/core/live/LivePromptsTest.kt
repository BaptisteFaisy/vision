package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePromptsTest {

    @Test
    fun `douze dangers exterieur par defaut, plafond client a seize`() {
        assertEquals(12, LivePrompts.default.size)
        assertEquals(
            listOf(
                "person", "car", "bicycle", "electric scooter", "pole",
                "sidewalk", "crosswalk", "wall", "window", "stairs",
                "door", "barrier",
            ),
            LivePrompts.default,
        )
        assertEquals(16, LivePrompts.MAX)
        assertEquals(LivePrompts.default, LivePrompts.normalize(LivePrompts.default))
    }

    @Test
    fun `portes et barrieres suivies en exterieur en plus des dangers`() {
        assertTrue("door" in LivePrompts.default)
        assertTrue("barrier" in LivePrompts.default)
    }

    @Test
    fun `preset exterieur : huit concepts de cheminement, sous le plafond`() {
        assertEquals(
            listOf("person", "pole", "bollard", "fence", "barrier", "gate", "sign", "tree"),
            LivePrompts.outdoor,
        )
        assertTrue(LivePrompts.outdoor.size <= LivePrompts.MAX)
        assertEquals(LivePrompts.outdoor, LivePrompts.normalize(LivePrompts.outdoor))
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
    fun `la liste est bornee a seize prompts`() {
        val many = (1..20).map { "objet$it" }
        val normalized = LivePrompts.normalize(many)
        assertEquals(LivePrompts.MAX, normalized.size)
        assertEquals("objet1", normalized.first())
        assertEquals("objet16", normalized.last())
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
