package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Maths du tracker (JUnit seul, sans Android) : stabilité de l'identité quand
 * la personne bouge, oubli après [maxMiss] trous, séparation des personnes.
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
        // +10 px : IoU élevé, même piste.
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
    fun `une piste oubliee apres maxMiss trous puis rouverte avec un nouvel id`() {
        val tracker = PersonTracker(maxMiss = 2)
        val id = tracker.update(listOf(person(100f, 100f, 200f, 300f)), 0L)[0].id
        assertEquals(id, tracker.update(emptyList(), 100L)[0].id)
        assertEquals(id, tracker.update(emptyList(), 200L)[0].id)
        assertTrue(tracker.update(emptyList(), 300L).isEmpty())
        val reopened = tracker.update(listOf(person(100f, 100f, 200f, 300f)), 400L)
        assertEquals(1, reopened.size)
        assertTrue(reopened[0].id != id)
    }

    @Test
    fun `une boite lointaine n'est pas appariee a une piste existante`() {
        val tracker = PersonTracker()
        tracker.update(listOf(person(10f, 10f, 60f, 200f)), 0L)
        val tracks = tracker.update(listOf(person(500f, 10f, 550f, 300f)), 100L)
        assertEquals(2, tracks.size)
    }

    @Test
    fun `iou vaut 1 sur boites identiques et 0 sur disjointes`() {
        val box = listOf(0.1f, 0.1f, 0.3f, 0.5f)
        assertEquals(1f, boxIou(box, box), 1e-6f)
        assertEquals(0f, boxIou(box, listOf(0.6f, 0.6f, 0.9f, 0.9f)), 1e-6f)
    }
}
