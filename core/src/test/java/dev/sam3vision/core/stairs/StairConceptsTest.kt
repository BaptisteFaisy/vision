package dev.sam3vision.core.stairs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L'escalier se reconnaît au libellé, le sens aussi — sinon on ne devine pas. */
class StairConceptsTest {

    @Test
    fun `escalier reconnu en francais et en anglais`() {
        listOf("stairs", "Stairs", "staircase", "escalier", "Escalier", "marches").forEach {
            assertTrue(it, StairConcepts.isStairs(it))
        }
        assertTrue(StairConcepts.isStairs("stairs going up"))
        assertTrue(StairConcepts.isStairs("downstairs"))
    }

    @Test
    fun `un obstacle ordinaire n'est pas un escalier`() {
        listOf("chaise", "personne", "porte", "wall", "").forEach {
            assertFalse(it, StairConcepts.isStairs(it))
        }
    }

    @Test
    fun `le sens vient des mots du libelle`() {
        assertEquals(StairDirection.Up, StairConcepts.direction("stairs going up"))
        assertEquals(StairDirection.Down, StairConcepts.direction("stairs going down"))
        assertEquals(StairDirection.Up, StairConcepts.direction("Escalier montant"))
        assertEquals(StairDirection.Down, StairConcepts.direction("escalier descendant"))
        assertEquals(StairDirection.Up, StairConcepts.direction("upstairs"))
        assertEquals(StairDirection.Down, StairConcepts.direction("downstairs"))
    }

    @Test
    fun `sans mot de sens, le sens est inconnu`() {
        assertEquals(StairDirection.Unknown, StairConcepts.direction("stairs"))
        assertEquals(StairDirection.Unknown, StairConcepts.direction("escalier"))
        assertEquals(StairDirection.Unknown, StairConcepts.direction("chaise"))
    }
}
