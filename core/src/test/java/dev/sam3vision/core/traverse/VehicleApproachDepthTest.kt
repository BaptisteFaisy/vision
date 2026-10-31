package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Veto profondeur du suivi véhicules (PISTE2) : le tau ne conclut que si la
 * proximité suit l'expansion — à profondeur constante (pan/zoom), silence.
 * Séquences synthétiques, miroir de `VehicleApproachTest`.
 */
class VehicleApproachDepthTest {

    /** Voiture centrée de [widthPx] de large, mesurée à [closeness] (`null` = muette). */
    private fun frame(approach: VehicleApproach, widthPx: Float, atMs: Long, closeness: Float?): ApproachVerdict {
        val detection = Detection("car", 0.9f, box(widthPx), width = 640, height = 480)
        val depth = closeness?.let { mapOf(detection.box to it) }
        return approach.update(listOf(detection), atMs = atMs, depthByBox = depth)
    }

    @Test
    fun `expansion a profondeur constante - le tau se tait (pan-zoom)`() {
        val approach = VehicleApproach()
        frame(approach, 100f, 0L, 0.5f)
        frame(approach, 110f, 100L, 0.5f)
        val third = frame(approach, 120f, 200L, 0.5f)

        assertFalse("même croissance que le veto historique, mais profondeur plate", third.approaching)
        assertNull(third.ttcSeconds)
        assertEquals("piste suivie mais muette", 1, approach.trackCount)
    }

    @Test
    fun `expansion a profondeur croissante - le tau tient`() {
        val approach = VehicleApproach()
        frame(approach, 100f, 0L, 0.4f)
        frame(approach, 110f, 100L, 0.5f)
        val third = frame(approach, 120f, 200L, 0.6f)

        assertTrue(third.approaching)
        assertNotNull(third.ttcSeconds)
        assertTrue("tau ~2,4 s", third.ttcSeconds!! in 1.0f..3.0f)
        assertEquals("car", third.label)
    }

    @Test
    fun `sans profondeur - repli geometrie (non-regression)`() {
        val approach = VehicleApproach()
        frame(approach, 100f, 0L, null)
        frame(approach, 110f, 100L, null)
        val third = frame(approach, 120f, 200L, null)

        assertTrue(third.approaching)
        assertNotNull(third.ttcSeconds)
    }

    @Test
    fun `profondeur partielle - pas de veto sans les deux mesures`() {
        val approach = VehicleApproach()
        frame(approach, 100f, 0L, null)
        frame(approach, 110f, 100L, 0.5f)
        val third = frame(approach, 120f, 200L, 0.5f)

        assertTrue("première mesure manquante : le doute ne tait pas", third.approaching)
    }

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }
}
