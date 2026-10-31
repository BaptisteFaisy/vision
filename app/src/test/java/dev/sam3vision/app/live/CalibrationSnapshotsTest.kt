package dev.sam3vision.app.live

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Visée d'étalonnage : la plus grande personne cadrée, fraîche et entière. */
class CalibrationSnapshotsTest {

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, 512, 384)

    @Test
    fun `plus grande personne retenue, le reste ignore`() {
        CalibrationSnapshots.clear()
        CalibrationSnapshots.note(
            listOf(
                detection("voiture", 0.95f, listOf(0f, 0f, 400f, 300f)),
                detection("personne", 0.9f, listOf(100f, 100f, 200f, 300f)),
                detection("homme", 0.9f, listOf(300f, 50f, 400f, 350f)),
                detection("enfant", 0.2f, listOf(0f, 0f, 100f, 383f)),
            ),
            512, 384, 1_000L,
        )
        val snap = CalibrationSnapshots.latest.value!!
        assertEquals(300f, snap.boxHeightPx, 0f)
        assertEquals(512, snap.imageWidthPx)
        assertEquals(1_000L, snap.atMs)
        CalibrationSnapshots.clear()
    }

    @Test
    fun `coupures postures et cadre vide ne calibrent pas`() {
        CalibrationSnapshots.clear()
        CalibrationSnapshots.note(
            listOf(detection("personne assise", 0.9f, listOf(100f, 0f, 200f, 300f))),
            512, 384, 1_000L,
        )
        assertNull(CalibrationSnapshots.latest.value)
        CalibrationSnapshots.note(emptyList(), 0, 0, 1_000L)
        assertNull(CalibrationSnapshots.latest.value)
    }

    @Test
    fun `fraicheur cinq minutes puis perimee`() {
        val snap = CalibrationSnap(200f, 512, 1_000L)
        assertTrue(CalibrationSnapshots.isFresh(snap, 1_000L))
        assertTrue(CalibrationSnapshots.isFresh(snap, 1_000L + CalibrationSnapshots.MAX_AGE_MS))
        assertFalse(CalibrationSnapshots.isFresh(snap, 1_000L + CalibrationSnapshots.MAX_AGE_MS + 1L))
        assertFalse(CalibrationSnapshots.isFresh(snap, 999L))
        assertFalse(CalibrationSnapshots.isFresh(null, 1_000L))
    }
}
