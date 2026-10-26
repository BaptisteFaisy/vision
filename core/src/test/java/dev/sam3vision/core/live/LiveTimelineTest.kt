package dev.sam3vision.core.live

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mémoire du direct : identité stable des objets, fenêtre de rétention, résumé
 * d'une fenêtre temporelle.
 */
class LiveTimelineTest {

    /** Détection en pixels : [x] et [y] sont normalisés (0..1) sur une image 100×100. */
    private fun detection(label: String, x: Float, y: Float = 0f, score: Float = 0.9f): Detection {
        val px = x * 100f
        val py = y * 100f
        return Detection(label, score, listOf(px, py, px + 20f, py + 20f), 100, 100)
    }

    @Test
    fun `le meme objet garde son identifiant d une frame a l autre`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.record(1_500L, listOf(detection("personne", 0.12f)))

        val summary = timeline.recent(1_500L, 10_000L)
        assertEquals(1, summary.tracks.size)
        assertEquals(2, summary.tracks.single().frames)
        assertEquals(1_000L, summary.tracks.single().firstAtMs)
        assertEquals(1_500L, summary.tracks.single().lastAtMs)
    }

    @Test
    fun `un objet qui change de place est un nouvel objet`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.record(1_500L, listOf(detection("personne", 0.8f)))

        val summary = timeline.recent(1_500L, 10_000L)
        assertEquals(2, summary.tracks.size)
    }

    @Test
    fun `deux labels differents sont deux pistes`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(detection("personne", 0.1f), detection("chien", 0.1f)))

        val summary = timeline.recent(1_000L, 10_000L)
        assertEquals(2, summary.tracks.size)
        assertEquals(listOf("personne", "chien"), summary.tracks.map { it.label })
    }

    @Test
    fun `la piste est oubliee apres le delai de suivi`() {
        val timeline = LiveTimeline(trackTimeoutMs = 1_000L)
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.record(5_000L, listOf(detection("personne", 0.1f)))

        val summary = timeline.recent(5_000L, 10_000L)
        assertEquals(2, summary.tracks.size)
    }

    @Test
    fun `les frames trop anciennes sortent de la memoire`() {
        val timeline = LiveTimeline(retentionMs = 2_000L)
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.record(10_000L, listOf(detection("chien", 0.1f)))

        assertEquals(1, timeline.size)
        val summary = timeline.recent(10_000L, 60_000L)
        assertEquals(listOf("chien"), summary.tracks.map { it.label })
    }

    @Test
    fun `le nombre de frames conservees est borne`() {
        val timeline = LiveTimeline(maxFrames = 3)
        repeat(10) { index -> timeline.record(index * 100L, listOf(detection("personne", 0.1f))) }

        assertEquals(3, timeline.size)
    }

    @Test
    fun `le resume ne garde que la fenetre demandee`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.record(5_000L, listOf(detection("chien", 0.1f)))

        val summary = timeline.summary(4_000L, 6_000L)
        assertEquals(listOf("chien"), summary.tracks.map { it.label })
    }

    @Test
    fun `une detection sans taille exploitable est ignoree`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(Detection("personne", 0.9f, listOf(1f, 1f, 2f, 2f), 0, 0)))

        assertTrue(timeline.recent(1_000L, 10_000L).tracks.isEmpty())
    }

    @Test
    fun `record rend l'identite de chaque detection dans son ordre`() {
        val timeline = LiveTimeline()
        val first = timeline.record(1_000L, listOf(detection("personne", 0.1f), detection("chien", 0.1f)))
        assertEquals(2, first.size)
        val personId = first[0]!!.id
        val dogId = first[1]!!.id
        assertEquals("personne", first[0]!!.label)
        assertEquals("chien", first[1]!!.label)

        // même place, même label : même identité ; une détection inexploitable = null
        val second = timeline.record(
            1_500L,
            listOf(
                detection("personne", 0.1f),
                Detection("chien", 0.9f, listOf(1f, 1f, 2f, 2f), 0, 0),
                detection("chien", 0.1f),
            ),
        )
        assertEquals(3, second.size)
        assertEquals(personId, second[0]!!.id)
        assertNull(second[1])
        assertEquals(dogId, second[2]!!.id)
    }

    @Test
    fun `clear vide la memoire`() {
        val timeline = LiveTimeline()
        timeline.record(1_000L, listOf(detection("personne", 0.1f)))
        timeline.clear()

        assertEquals(0, timeline.size)
        assertTrue(timeline.recent(1_000L, 10_000L).tracks.isEmpty())
    }
}
