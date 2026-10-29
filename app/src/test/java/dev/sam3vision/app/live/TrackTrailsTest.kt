package dev.sam3vision.app.live

import dev.sam3vision.core.model.Detection
import dev.sam3vision.core.model.SeenObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Chemins centraux des objets suivis : le centre de chaque boîte identifiée
 * allonge le chemin de sa piste, un trou d'une frame ne l'efface pas, les
 * vieilles pistes sont oubliées.
 */
class TrackTrailsTest {

    private val width = 512
    private val height = 288

    /** Une détection carrée de 80 px centrée en ([centerX], 140). */
    private fun detection(label: String, centerX: Float): Detection =
        Detection(label, 0.9f, listOf(centerX - 40f, 100f, centerX + 40f, 180f), width, height)

    private fun seen(id: Int, label: String, centerX: Float): SeenObject =
        SeenObject(id, label, 0.9f, listOf((centerX - 40f) / width, 100f / height, (centerX + 40f) / width, 180f / height))

    @Test
    fun `sans frame rien n'est trace`() {
        assertTrue(TrailLayer().empty)
        assertTrue(CenterTrails().observeFrame(0L, 0, 0, emptyList(), emptyList()).empty)
    }

    @Test
    fun `le centre de chaque boite allonge le chemin de sa piste`() {
        val trails = CenterTrails()
        val first = trails.observeFrame(
            0L, width, height,
            listOf(detection("personne", 100f), detection("chien", 300f)),
            listOf(seen(1, "personne", 100f), seen(2, "chien", 300f)),
        )
        assertEquals(2, first.trails.size)
        assertEquals(TrailPoint(100f / width, 140f / height), first.trails[0].points.single())
        assertEquals(0, first.trails[0].colorIndex)
        assertEquals(1, first.trails[1].colorIndex)

        val second = trails.observeFrame(
            100L, width, height,
            listOf(detection("personne", 120f), detection("chien", 300f)),
            listOf(seen(1, "personne", 120f), seen(2, "chien", 300f)),
        )
        assertEquals(2, second.trails[0].points.size)
        assertEquals(TrailPoint(120f / width, 140f / height), second.trails[0].points.last())
        assertEquals(2, second.trails[1].points.size)
    }

    @Test
    fun `une detection sans identite n'ouvre pas de piste`() {
        val trails = CenterTrails()
        val layer = trails.observeFrame(0L, width, height, listOf(detection("personne", 100f)), listOf(null))
        assertTrue(layer.empty)
        assertEquals(0, trails.trackCount)
    }

    @Test
    fun `un trou d'une frame garde le chemin, une piste perimee est oubliee`() {
        val trails = CenterTrails(timeoutMs = 200L)
        trails.observeFrame(0L, width, height, listOf(detection("personne", 100f)), listOf(seen(1, "personne", 100f)))
        val holed = trails.observeFrame(100L, width, height, emptyList(), emptyList())
        assertEquals(1, holed.trails.size)
        assertEquals(1, holed.trails.single().points.size)
        val forgotten = trails.observeFrame(500L, width, height, emptyList(), emptyList())
        assertTrue(forgotten.empty)
        assertEquals(0, trails.trackCount)
    }

    @Test
    fun `le chemin est borne aux points les plus recents`() {
        val trails = CenterTrails(maxPoints = 3)
        repeat(5) { step ->
            trails.observeFrame(
                step * 100L, width, height,
                listOf(detection("personne", 100f + step * 10)),
                listOf(seen(1, "personne", 100f + step * 10)),
            )
        }
        val layer = trails.observeFrame(500L, width, height, emptyList(), emptyList())
        assertEquals(3, layer.trails.single().points.size)
        assertEquals(TrailPoint(120f / width, 140f / height), layer.trails.single().points.first())
    }

    @Test
    fun `reset oublie tous les chemins`() {
        val trails = CenterTrails()
        trails.observeFrame(0L, width, height, listOf(detection("personne", 100f)), listOf(seen(1, "personne", 100f)))
        trails.reset()
        assertEquals(0, trails.trackCount)
        assertTrue(trails.observeFrame(100L, width, height, emptyList(), emptyList()).empty)
    }
}
