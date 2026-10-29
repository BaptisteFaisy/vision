package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracker façon ByteTrack (JUnit seul, sans Android) : identité stable quand
 * la personne bouge, réactivation après une courte occlusion, oubli après le
 * buffer, maintien par les boîtes faibles, nouveautés confirmées à la 2e vue.
 */
class PersonTrackerTest {

    private fun person(x1: Float, y1: Float, x2: Float, y2: Float, score: Float = 0.8f): Detection =
        Detection("personne", score, listOf(x1, y1, x2, y2), 640, 480)

    @Test
    fun `la meme personne qui bouge garde son id et vieillit`() {
        val tracker = PersonTracker()
        val first = tracker.update(listOf(person(100f, 100f, 200f, 300f)), 0L)
        assertEquals(1, first.size)
        val id = first[0].id
        // +10 px : coût faible, même piste.
        val second = tracker.update(listOf(person(110f, 100f, 210f, 300f)), 100L)
        assertEquals(listOf(id), second.map { it.id })
        assertEquals(2, second[0].frames)
        assertEquals(0, second[0].missed)
    }

    @Test
    fun `deux personnes separees ouvrent deux pistes`() {
        val tracker = PersonTracker()
        val tracks = tracker.update(
            listOf(person(10f, 10f, 60f, 200f), person(500f, 10f, 550f, 200f)),
            0L,
        )
        assertEquals(2, tracks.size)
        assertEquals(2, tracks.map { it.id }.toSet().size)
    }

    @Test
    fun `une courte occlusion garde l'id, une longue en donne un nouveau`() {
        val tracker = PersonTracker(ByteTrackParams(maxLostFrames = 2))
        val box = person(100f, 100f, 200f, 300f)
        val id = tracker.update(listOf(box), 0L)[0].id
        // 1 frame cachée : perdue (pas sortie) mais réactivée au retour.
        assertTrue(tracker.update(emptyList(), 100L).isEmpty())
        assertEquals(id, tracker.update(listOf(box), 200L)[0].id)
        // 3 frames cachées : oubliée, le retour ouvre une piste neuve (2 vues).
        tracker.update(emptyList(), 300L)
        tracker.update(emptyList(), 400L)
        tracker.update(emptyList(), 500L)
        assertTrue(tracker.update(listOf(box), 600L).isEmpty())
        val reopened = tracker.update(listOf(box), 700L)
        assertEquals(1, reopened.size)
        assertTrue(reopened[0].id != id)
    }

    @Test
    fun `une boite faible maintient la piste au second temps`() {
        val tracker = PersonTracker()
        val id = tracker.update(listOf(person(100f, 100f, 200f, 300f, 0.9f)), 0L)[0].id
        // Score 0,15 : temps faible, même piste en sortie.
        val kept = tracker.update(listOf(person(102f, 100f, 202f, 300f, 0.15f)), 100L)
        assertEquals(listOf(id), kept.map { it.id })
    }

    @Test
    fun `une boite lointaine ouvre une piste confirmee a la vue suivante`() {
        val tracker = PersonTracker()
        val idA = tracker.update(listOf(person(10f, 10f, 60f, 200f)), 0L)[0].id
        // Frame 2 : A perdue (pas sortie), B nouvelle non confirmée (pas sortie).
        assertTrue(tracker.update(listOf(person(500f, 10f, 550f, 300f)), 100L).isEmpty())
        // Frame 3 : B confirmée, nouvel id.
        val third = tracker.update(listOf(person(500f, 10f, 550f, 300f)), 200L)
        assertEquals(1, third.size)
        assertTrue(third[0].id != idA)
    }

    @Test
    fun `une nouveaute sans lendemain est supprimee`() {
        val tracker = PersonTracker()
        tracker.update(listOf(person(10f, 10f, 60f, 200f)), 0L)
        tracker.update(listOf(person(500f, 10f, 550f, 300f)), 100L)
        // B non confirmée + rien en frame 3 : supprimée, le retour repart à neuf.
        assertTrue(tracker.update(emptyList(), 200L).isEmpty())
        val back = tracker.update(listOf(person(500f, 10f, 550f, 300f)), 300L)
        assertTrue(back.isEmpty())
        val confirmed = tracker.update(listOf(person(500f, 10f, 550f, 300f)), 400L)
        assertEquals(1, confirmed.size)
    }

    @Test
    fun `iou vaut 1 sur boites identiques et 0 sur disjointes`() {
        val box = listOf(0.1f, 0.1f, 0.3f, 0.5f)
        assertEquals(1f, boxIou(box, box), 1e-6f)
        assertEquals(0f, boxIou(box, listOf(0.6f, 0.6f, 0.9f, 0.9f)), 1e-6f)
    }
}
