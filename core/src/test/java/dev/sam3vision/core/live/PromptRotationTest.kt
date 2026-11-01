package dev.sam3vision.core.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rotation 3 groupes : `person` partout, les 15 autres en round-robin —
 * découpage exhaustif (chaque concept suivi), sans trou ni doublon, cyclé par
 * [PromptRotation.groupFor]. Pur, déterministe.
 */
class PromptRotationTest {

    @Test
    fun `defaut 16 concepts en 3 groupes de 6 6 6`() {
        val split = PromptRotation.split(LivePrompts.default)
        assertEquals(3, split.size)
        assertEquals(listOf(6, 6, 6), split.map { it.size })
    }

    @Test
    fun `person dans chaque groupe`() {
        for (groups in listOf(PromptRotation.split(LivePrompts.default), PromptRotation.split(LivePrompts.outdoor))) {
            assertTrue(groups.all { group -> group.any { it.equals("person", ignoreCase = true) } })
        }
    }

    @Test
    fun `decoupage exhaustif sans doublon hors person`() {
        val split = PromptRotation.split(LivePrompts.default)
        val rest = split.flatten().filter { !it.equals("person", ignoreCase = true) }
        assertEquals("15 autres concepts, une fois chacun", 15, rest.size)
        assertEquals(15, rest.toSet().size)
        assertEquals(LivePrompts.default.toSet(), split.flatten().toSet())
    }

    @Test
    fun `ordre d'envoi preserve dans les groupes`() {
        val split = PromptRotation.split(LivePrompts.default)
        // Round-robin : groupe 0 prend les rangs 0, 3, 6… des non-personne.
        val rest = LivePrompts.default.filter { !it.equals("person", ignoreCase = true) }
        val group0 = split[0].filter { !it.equals("person", ignoreCase = true) }
        assertEquals(listOf(rest[0], rest[3], rest[6], rest[9], rest[12]), group0)
    }

    @Test
    fun `groupFor cycle 0 1 2 0`() {
        val split = PromptRotation.split(LivePrompts.default)
        assertEquals(split[0], PromptRotation.groupFor(split, 0))
        assertEquals(split[1], PromptRotation.groupFor(split, 1))
        assertEquals(split[2], PromptRotation.groupFor(split, 2))
        assertEquals(split[0], PromptRotation.groupFor(split, 3))
        assertEquals(split[1], PromptRotation.groupFor(split, 100))
    }

    @Test
    fun `sans person, round-robin pur exhaustif`() {
        val prompts = listOf("door", "chair", "table", "wall")
        val split = PromptRotation.split(prompts)
        assertEquals(4, split.flatten().size)
        assertEquals(prompts.toSet(), split.flatten().toSet())
        assertEquals(listOf("door", "wall"), split[0])
    }

    @Test
    fun `person seule, les 3 groupes la portent`() {
        val split = PromptRotation.split(listOf("person"))
        assertEquals(3, split.size)
        assertTrue(split.all { it == listOf("person") })
    }

    @Test
    fun `liste vide, groupes vides (repli appelant)`() {
        val split = PromptRotation.split(emptyList())
        assertEquals(3, split.size)
        assertTrue(split.all { it.isEmpty() })
        assertTrue(PromptRotation.groupFor(split, 0).isEmpty())
    }

    @Test
    fun `vides et doublons ecartes, casse du toujours-present ignoree`() {
        val split = PromptRotation.split(listOf("Person", "  ", "door", "door", "Person"))
        assertEquals(4, split.flatten().size) // Person x3 + door x1
        assertTrue(split.all { it.contains("Person") })
    }

    @Test
    fun `find the X au plafond - 17 concepts, groupes de 7 6 6`() {
        val full = LivePrompts.withConcept(LivePrompts.default, "suitcase")
        assertEquals(17, full.size)
        val split = PromptRotation.split(full)
        assertEquals(listOf(7, 6, 6), split.map { it.size })
        assertTrue(split.flatten().contains("suitcase"))
    }
}
