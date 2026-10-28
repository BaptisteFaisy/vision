package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthReadingsTest {

    /** Carte 100×100 : proximité 10 à gauche, 2 à droite (marche franche à x=50). */
    private fun stepMap(): DepthMap {
        val values = FloatArray(100 * 100) { i -> if (i % 100 < 50) 10f else 2f }
        return DepthMap(100, 100, values)
    }

    @Test
    fun `mediane par boite - cote proche et cote loin`() {
        val map = stepMap()
        val near = DepthReadings.boxCloseness(map, listOf(0f, 0f, 49f, 99f), 100, 100)
        val far = DepthReadings.boxCloseness(map, listOf(50f, 0f, 99f, 99f), 100, 100)
        assertEquals(10f, near!!, 0.001f)
        assertEquals(2f, far!!, 0.001f)
    }

    @Test
    fun `boite hors champ - bornee sans planter, vide - null`() {
        val map = stepMap()
        val clipped = DepthReadings.boxCloseness(map, listOf(-50f, -50f, 10f, 10f), 100, 100)
        assertEquals(10f, clipped!!, 0.001f)
        assertNull(DepthReadings.boxCloseness(map, listOf(0f, 0f, 10f), 100, 100))
        assertNull(DepthReadings.boxCloseness(map, listOf(0f, 0f, 10f, 10f), 0, 100))
    }

    @Test
    fun `echelle - mediane des ancres, vide - null`() {
        val scale = DepthReadings.scaleFromAnchors(
            listOf(DepthAnchor(2f, 10f), DepthAnchor(10f, 2f), DepthAnchor(4f, 5f)),
        )
        assertEquals(20f, scale!!, 0.001f)
        assertNull(DepthReadings.scaleFromAnchors(emptyList()))
        assertNull(DepthReadings.scaleFromAnchors(listOf(DepthAnchor(-1f, 10f))))
    }

    @Test
    fun `affine - remplace ajoute et garde`() {
        val map = stepMap()
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val sidewalk = Detection("trottoir", 0.7f, listOf(50f, 0f, 99f, 99f), 100, 100)
        val ghost = Detection("reflet", 0.4f, listOf(200f, 200f, 300f, 300f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val out = DepthReadings.refine(100, 100, listOf(person, sidewalk, ghost), pinhole, map)
        assertEquals(2, out.size)
        val p = out.first { it.label == "personne" }
        assertEquals(7, p.trackId)
        assertEquals(2f, p.meters, 0.01f)
        val s = out.first { it.label == "trottoir" }
        assertEquals(-1, s.trackId)
        assertEquals(10f, s.meters, 0.01f)
        assertTrue(out.none { it.label == "reflet" })
    }

    @Test
    fun `affine - sans ancre garde le stenepe tel quel`() {
        val map = stepMap()
        val person = Detection("personne", 0.9f, listOf(0f, 0f, 49f, 99f), 100, 100)
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, person.box))
        val broken = DepthMap(100, 100, FloatArray(100 * 100) { Float.NaN })
        val out = DepthReadings.refine(100, 100, listOf(person), pinhole, broken)
        assertEquals(pinhole, out)
    }
}
