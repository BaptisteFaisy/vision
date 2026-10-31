package dev.sam3vision.app

import dev.sam3vision.app.isee.CrossingLight
import dev.sam3vision.core.crossing.CrossingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Publication du feu du mode Traversée dans l'état du direct : c'est ce couple
 * (état, boîte) que l'overlay dessine. Rien de publié ⇒ rien de dessiné.
 */
class LiveCrossingPublishTest {

    private val box = listOf(0.2f, 0.3f, 0.5f, 0.8f)

    @Test
    fun `mode actif avec feu publie etat et boite`() {
        val out = LiveUiState().withCrossing(true, CrossingLight(box, CrossingState.Green))
        assertEquals(CrossingState.Green, out.crossing)
        assertEquals(box, out.crossingBox)
    }

    @Test
    fun `mode coupe ne publie rien meme avec un feu en memoire`() {
        val out = LiveUiState().withCrossing(false, CrossingLight(box, CrossingState.Red))
        assertNull(out.crossing)
        assertNull(out.crossingBox)
    }

    @Test
    fun `aucun feu lu laisse l etat tel quel`() {
        val before = LiveUiState(crossing = CrossingState.Red, crossingBox = box)
        assertEquals(before, before.withCrossing(true, null))
    }

    @Test
    fun `le rouge se publie comme un etat a afficher`() {
        val out = LiveUiState().withCrossing(true, CrossingLight(box, CrossingState.Red))
        assertEquals(CrossingState.Red, out.crossing)
        assertEquals(box, out.crossingBox)
    }
}
