package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread profondeur du veto (PISTE2) : les proximités traversent jusqu'au
 * suivi, qui invalide son tau à profondeur constante — le veto instantané
 * (croissance deux frames) reste lui volontairement géométrique.
 * Focale ≤ 0 : pas de distance, seule l'approche compte.
 */
class TraverseVetoDepthTest {

    private fun detection(box: List<Float>): Detection =
        Detection("car", 0.9f, box, width = 640, height = 480)

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** Frame datée, mesurée à [closeness] (`null` = profondeur muette). */
    private fun frame(veto: TraverseVeto, widthPx: Float, atMs: Long, closeness: Float?): TraverseDecision {
        val det = detection(box(widthPx))
        return veto.update(
            lightGreen = true,
            detections = listOf(det),
            focalPx = -1f,
            atMs = atMs,
            depthByBox = closeness?.let { mapOf(det.box to it) },
        )
    }

    @Test
    fun `profondeur constante - le suivi se tait, l instantane bloque (conservateur)`() {
        val veto = TraverseVeto()
        frame(veto, 100f, 0L, 0.5f)
        frame(veto, 110f, 100L, 0.5f)
        val third = frame(veto, 120f, 200L, 0.5f)

        assertTrue("croissance deux frames : l'instantané bloque", third.veto)
        assertEquals("car", third.blockingLabel)
        assertNull("tau suivi invalidé par le pallier de profondeur", third.approachTtcSeconds)
    }

    @Test
    fun `profondeur croissante - le suivi confirme avec son ttc`() {
        val veto = TraverseVeto()
        frame(veto, 100f, 0L, 0.4f)
        frame(veto, 110f, 100L, 0.5f)
        val third = frame(veto, 120f, 200L, 0.6f)

        assertTrue(third.veto)
        assertNotNull(third.approachTtcSeconds)
        assertTrue("tau ~2,4 s", third.approachTtcSeconds!! in 1.0f..3.0f)
    }

    @Test
    fun `sans profondeur - repli geometrie (non-regression)`() {
        val veto = TraverseVeto()
        frame(veto, 100f, 0L, null)
        frame(veto, 110f, 100L, null)
        val third = frame(veto, 120f, 200L, null)

        assertTrue(third.veto)
        assertNotNull(third.approachTtcSeconds)
    }
}
