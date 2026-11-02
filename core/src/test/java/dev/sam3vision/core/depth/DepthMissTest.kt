package dev.sam3vision.core.depth

import dev.sam3vision.core.distance.DistanceReading
import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Repli DEPTH_ONLY : un objet sans distance est nommé avec sa cause, jamais tu. */
class DepthMissTest {

    private fun stairs(box: List<Float> = listOf(10f, 10f, 50f, 60f)) =
        Detection("stairs", 0.8f, box, 100, 100)

    private fun person(box: List<Float> = listOf(60f, 10f, 90f, 80f)) =
        Detection("personne", 0.9f, box, 100, 100)

    @Test
    fun `profondeur seule sans ancre - repli nomme, cause ancre`() {
        val stair = stairs()
        val out = DepthReadings.refineStatusWithCloseness(
            listOf(stair), emptyList(), mapOf(stair.box to 0.5f),
        )
        assertFalse(out.calibrated)
        assertTrue(out.readings.isEmpty())
        val miss = out.unmeasured.single()
        assertEquals("stairs", miss.label)
        assertEquals(stair.box, miss.box)
        assertEquals(DepthReadings.DepthMissReason.NO_ANCHOR, miss.reason)
        assertFalse(miss.cutBottom)
    }

    @Test
    fun `bas coupe sans ancre - repli nomme, sol coupe signale`() {
        val stair = stairs(listOf(10f, 10f, 50f, 99f))
        val out = DepthReadings.refineStatusWithCloseness(
            listOf(stair), emptyList(), mapOf(stair.box to 0.5f),
        )
        val miss = out.unmeasured.single()
        assertEquals(DepthReadings.DepthMissReason.NO_ANCHOR, miss.reason)
        assertTrue(miss.cutBottom)
    }

    @Test
    fun `calibre sans proximite - cause proximite`() {
        val human = person()
        val stair = stairs()
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, human.box))
        val out = DepthReadings.refineStatusWithCloseness(
            listOf(human, stair), pinhole, mapOf(human.box to 0.8f),
        )
        assertTrue(out.calibrated)
        assertEquals(1, out.readings.size)
        assertEquals("personne", out.readings.single().label)
        val miss = out.unmeasured.single()
        assertEquals("stairs", miss.label)
        assertEquals(DepthReadings.DepthMissReason.NO_CLOSENESS, miss.reason)
    }

    @Test
    fun `proximite hors bornes - cause bornes, pas bornee deguisee`() {
        val human = person()
        val stair = stairs()
        val pinhole = listOf(DistanceReading(7, "personne", 2f, 2f, human.box))
        val out = DepthReadings.refineStatusWithCloseness(
            listOf(human, stair), pinhole, mapOf(human.box to 0.8f, stair.box to 0.01f),
        )
        assertTrue(out.calibrated)
        assertTrue(out.readings.none { it.label == "stairs" })
        assertEquals(DepthReadings.DepthMissReason.OUT_OF_BOUNDS, out.unmeasured.single().reason)
    }

    @Test
    fun `base sol gardee sans ancre - pas de repli, pas de bruit`() {
        val stair = stairs()
        val ground = DistanceReading(3, "stairs", 4f, 4f, stair.box, 0.5f)
        val broken = DepthMap(100, 100, FloatArray(100 * 100) { Float.NaN })
        val out = DepthReadings.refineWithStatus(100, 100, listOf(stair), listOf(ground), broken)
        assertFalse(out.calibrated)
        assertEquals(listOf(ground), out.readings)
        assertTrue(out.unmeasured.isEmpty())
    }

    @Test
    fun `carte cassee sans base - repli nomme par refineWithStatus`() {
        val stair = stairs()
        val broken = DepthMap(100, 100, FloatArray(100 * 100) { Float.NaN })
        val out = DepthReadings.refineWithStatus(100, 100, listOf(stair), emptyList(), broken)
        assertFalse(out.calibrated)
        assertTrue(out.readings.isEmpty())
        assertEquals(DepthReadings.DepthMissReason.NO_ANCHOR, out.unmeasured.single().reason)
    }

    @Test
    fun `autre label omis - pas de repli, silence inchange`() {
        val ghost = Detection("reflet", 0.4f, listOf(200f, 200f, 300f, 300f), 100, 100)
        val out = DepthReadings.refineStatusWithCloseness(listOf(ghost), emptyList(), emptyMap())
        assertTrue(out.readings.isEmpty())
        assertTrue(out.unmeasured.isEmpty())
    }

    @Test
    fun `crochet appele - le log recoit le repli`() {
        val seen = ArrayList<DepthReadings.DepthMiss>()
        val stair = stairs()
        DepthReadings.refineStatusWithCloseness(
            listOf(stair), emptyList(), mapOf(stair.box to 0.5f),
            onUnmeasured = { seen += it },
        )
        assertEquals("stairs", seen.single().label)
        assertEquals(DepthReadings.DepthMissReason.NO_ANCHOR, seen.single().reason)
    }
}
