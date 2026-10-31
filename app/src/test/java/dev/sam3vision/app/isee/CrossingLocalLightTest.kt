package dev.sam3vision.app.isee

import dev.sam3vision.core.crossing.CrossingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Seau local de la couleur du feu : cadence, boîte manquante, remise à zéro.
 * La lecture elle-même est injectée — l'Android n'entre pas ici.
 */
class CrossingLocalLightTest {

    private val box = listOf(0.4f, 0.2f, 0.6f, 0.6f)
    private val reads = mutableListOf<Pair<String, List<Float>>>()

    private fun pump(
        boxOf: () -> List<Float>? = { box },
        state: CrossingState = CrossingState.Green,
    ) = CrossingLocalLight(
        scope = CoroutineScope(Dispatchers.Unconfined),
        background = Dispatchers.Unconfined,
        boxOf = boxOf,
        read = { jpeg, b -> reads.add(jpeg to b); state },
    )

    @Test
    fun `la premiere frame lit le feu et publie la boite du serveur`() {
        val light = pump()
        light.offerFrame("jpeg", nowMs = 1_000)

        assertEquals(1, reads.size)
        assertEquals(CrossingState.Green, light.ui.value?.state)
        assertEquals(box, light.ui.value?.box)
    }

    @Test
    fun `deux frames dans les 150 ms ne lisent qu une fois`() {
        val light = pump()
        light.offerFrame("a", nowMs = 1_000)
        light.offerFrame("b", nowMs = 1_040)
        light.offerFrame("c", nowMs = 1_149)

        assertEquals(1, reads.size)
    }

    @Test
    fun `a 150 ms la lecture repart`() {
        val light = pump()
        light.offerFrame("a", nowMs = 1_000)
        light.offerFrame("b", nowMs = 1_150)

        assertEquals(2, reads.size)
        assertEquals("b", reads.last().first)
    }

    @Test
    fun `sans boite connue rien n est lu`() {
        val light = pump(boxOf = { null })
        light.offerFrame("jpeg", nowMs = 1_000)

        assertEquals(0, reads.size)
        assertNull(light.ui.value)
    }

    @Test
    fun `une frame vide ne consomme pas le creneau`() {
        val light = pump()
        light.offerFrame("", nowMs = 1_000)
        light.offerFrame("jpeg", nowMs = 1_010)

        assertEquals(1, reads.size)
    }

    @Test
    fun `clear efface l affichage et rend le creneau`() {
        val light = pump()
        light.offerFrame("jpeg", nowMs = 1_000)
        light.clear()
        assertNull(light.ui.value)

        light.offerFrame("jpeg2", nowMs = 1_010)
        assertEquals(2, reads.size)
    }

    @Test
    fun `le rouge se publie comme un etat`() {
        val light = pump(state = CrossingState.Red)
        light.offerFrame("jpeg", nowMs = 1_000)
        assertEquals(CrossingState.Red, light.ui.value?.state)
    }
}
