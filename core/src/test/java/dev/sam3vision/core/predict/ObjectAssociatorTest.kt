package dev.sam3vision.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Association sans ids serveur : le pont nécessaire pour le mode `/live`. */
class ObjectAssociatorTest {

    private val associator = ObjectAssociator()

    private fun obj(label: String, x: Float) = ServerObject(0, label, 0.9f, Box(x, 100f, x + 40f, 200f))

    @Test
    fun `associe par recouvrement decroissant`() {
        val tracks = mapOf(1 to obj("a", 100f), 2 to obj("b", 300f))
        val server = listOf(obj("a", 104f), obj("b", 302f))
        val assigned = associator.assign(server, tracks)
        assertEquals(2, assigned.size)
        assertEquals(104f, assigned.getValue(1).box.x1, 0.01f)
        assertEquals(302f, assigned.getValue(2).box.x1, 0.01f)
    }

    @Test
    fun `un suivi ne prend qu'un objet et inversement`() {
        val tracks = mapOf(1 to obj("a", 100f))
        val server = listOf(obj("a", 102f), obj("a2", 110f))
        assertEquals(1, associator.assign(server, tracks).size)
    }

    @Test
    fun `sans recouvrement rien n'est associe`() {
        val tracks = mapOf(1 to obj("a", 100f))
        assertTrue(associator.assign(listOf(obj("b", 400f)), tracks).isEmpty())
    }

    @Test
    fun `un objet apparu est laisse a l'appelant`() {
        val tracks = mapOf(1 to obj("a", 100f))
        val assigned = associator.assign(listOf(obj("a", 103f), obj("c", 500f)), tracks)
        assertEquals(setOf(1), assigned.keys)
    }

    @Test
    fun `freeId prend le plus petit identifiant libre`() {
        assertEquals(1, associator.freeId(emptySet()))
        assertEquals(3, associator.freeId(setOf(1, 2, 4)))
    }
}