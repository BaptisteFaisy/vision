package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.SegObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraverseStateTest {

    private fun det(label: String = "person", score: Float = 0.7f): SegObject =
        SegObject(label, score, listOf(0f, 0f, 1f, 1f), "")

    @Test
    fun `une seule detection ne confirme ni n affiche`() {
        val state = TraverseState()
        assertTrue(state.update(listOf(det())).isEmpty())
        assertFalse(state.isConfirmed("person"))
    }

    @Test
    fun `deux detections confirment et affichent`() {
        val state = TraverseState()
        state.update(listOf(det()))
        val shown = state.update(listOf(det()))
        assertEquals(1, shown.size)
        assertEquals("person", shown.single().label)
        assertTrue(state.isConfirmed("person"))
    }

    @Test
    fun `la confirmation exige des detections consecutives`() {
        val state = TraverseState()
        state.update(listOf(det()))
        state.update(emptyList())
        state.update(listOf(det()))
        assertFalse("trou au milieu : pas confirme", state.isConfirmed("person"))
        state.update(listOf(det()))
        assertTrue(state.isConfirmed("person"))
    }

    @Test
    fun `un flash isole ne s affiche jamais`() {
        val state = TraverseState()
        state.update(listOf(det()))
        assertTrue(state.update(emptyList()).isEmpty())
        state.update(listOf(det()))
        assertTrue(state.update(emptyList()).isEmpty())
        assertFalse(state.isConfirmed("person"))
    }

    @Test
    fun `clignotement bref garde la piste affichee boite gelee`() {
        val state = TraverseState()
        state.update(listOf(det()))
        val live = state.update(listOf(det())).single()
        val frozen = state.update(emptyList())
        assertEquals("1 frame manquante toleree", 1, frozen.size)
        assertEquals(live, frozen.single())
        assertEquals(1, state.update(emptyList()).size)
        assertTrue("3e manquante : decroche", state.update(emptyList()).isEmpty())
        assertFalse(state.isConfirmed("person"))
    }

    @Test
    fun `score faible confirme mais n affiche pas tant que display non atteint`() {
        val state = TraverseState()
        state.update(listOf(det(score = 0.55f)))
        state.update(listOf(det(score = 0.55f)))
        assertTrue("confirme mais sous le seuil d affichage", state.isConfirmed("person"))
        assertTrue(state.update(listOf(det(score = 0.55f))).isEmpty())
        assertEquals(1, state.update(listOf(det(score = 0.65f))).size)
    }

    @Test
    fun `la bande d hysteresis garde l affichage sans scintiller`() {
        val state = TraverseState()
        state.update(listOf(det()))
        state.update(listOf(det()))
        assertEquals(1, state.update(listOf(det(score = 0.45f))).size)
        assertEquals(1, state.update(listOf(det(score = 0.55f))).size)
        assertEquals(1, state.update(listOf(det(score = 0.45f))).size)
    }

    @Test
    fun `sous le decrochage ca compte manque mais tolere`() {
        val state = TraverseState()
        state.update(listOf(det()))
        val live = state.update(listOf(det())).single()
        val kept = state.update(listOf(det(score = 0.35f)))
        assertEquals("boite gelee, pas la faible", live, kept.single())
        assertEquals(1, state.update(listOf(det(score = 0.35f))).size)
        assertTrue("3e sous-seuil : decroche", state.update(listOf(det(score = 0.35f))).isEmpty())
    }

    @Test
    fun `retour du signal remet le compteur de manque a zero`() {
        val state = TraverseState()
        state.update(listOf(det()))
        state.update(listOf(det()))
        state.update(emptyList())
        state.update(emptyList())
        state.update(listOf(det()))
        state.update(emptyList())
        state.update(emptyList())
        assertTrue("encore la apres 2 trous", state.isConfirmed("person"))
        assertTrue(state.update(emptyList()).isEmpty())
    }

    @Test
    fun `labels independants et meilleur score garde`() {
        val state = TraverseState()
        state.update(listOf(det("person", 0.5f), det("person", 0.8f), det("chair", 0.9f)))
        val shown = state.update(listOf(det("person", 0.8f), det("chair", 0.9f)))
        assertEquals(2, shown.size)
        assertEquals(0.8f, shown.first { it.label == "person" }.score)
        state.update(listOf(det("chair", 0.9f)))
        state.update(listOf(det("chair", 0.9f)))
        state.update(listOf(det("chair", 0.9f)))
        assertFalse(state.isConfirmed("person"))
        assertTrue(state.isConfirmed("chair"))
    }

    @Test
    fun `reset vide toutes les pistes`() {
        val state = TraverseState()
        state.update(listOf(det()))
        state.update(listOf(det()))
        state.reset()
        assertFalse(state.isConfirmed("person"))
        assertTrue(state.confirmedLabels.isEmpty())
        state.update(listOf(det()))
        assertFalse("reconfirmation exigee", state.isConfirmed("person"))
    }

    @Test
    fun `les champs vision du SegObject survivent au suivi`() {
        val state = TraverseState()
        val rich = SegObject("car", 0.8f, listOf(0f, 0f, 1f, 1f), "", depth = 0.9f)
        state.update(listOf(rich))
        val shown = state.update(listOf(rich)).single()
        assertEquals(0.9f, shown.depth)
        assertEquals(rich, state.update(emptyList()).single())
    }
}
