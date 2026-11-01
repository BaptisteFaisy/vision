package dev.sam3vision.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fusion ego-motion (gyro-arret) : le fond optique decide seul quand il est
 * utilisable, le gyro ne prend le relais qu'en repli (pan rapide qui sature
 * le bloc-matching) — le gyro ne contredit jamais un fond sur, et sans source
 * le doute ne parle jamais.
 */
class EgoMotionTest {

    companion object {
        private const val FOCAL_PX = 100f
        private const val CONFIDENT = 0.9f
        private const val EPSILON = 1e-5f
    }

    @Test
    fun `hintFor - delta frais donne le deplacement attendu`() {
        val hint = EgoMotion.hintFor(StampedDelta(GyroDelta(0.05f, -0.02f), 50L), FOCAL_PX)!!
        assertEquals(5f, hint.dxPx, EPSILON)
        assertEquals(-2f, hint.dyPx, EPSILON)
        assertEquals(1f, hint.confidence, EPSILON)
    }

    @Test
    fun `hintFor - confiance explicite conservee`() {
        val hint = EgoMotion.hintFor(StampedDelta(GyroDelta(0.01f, 0f), 10L), FOCAL_PX, 0.5f)!!
        assertEquals(0.5f, hint.confidence, EPSILON)
    }

    @Test
    fun `hintFor - sans avis quand le delta est inutilisable`() {
        assertNull(EgoMotion.hintFor(null, FOCAL_PX))
        assertNull("perime", EgoMotion.hintFor(StampedDelta(GyroDelta(0.04f, 0f), GyroCompensation.MAX_AGE_MS + 1L), FOCAL_PX))
        assertNull("age negatif", EgoMotion.hintFor(StampedDelta(GyroDelta(0.04f, 0f), -1L), FOCAL_PX))
        assertNull("rotation excessive", EgoMotion.hintFor(StampedDelta(GyroDelta(GyroCompensation.MAX_DELTA_RAD + 0.01f, 0f), 10L), FOCAL_PX))
        assertNull("delta non fini", EgoMotion.hintFor(StampedDelta(GyroDelta(Float.NaN, 0f), 10L), FOCAL_PX))
        assertNull("focale nulle", EgoMotion.hintFor(StampedDelta(GyroDelta(0.04f, 0f), 10L), 0f))
        assertNull("focale non finie", EgoMotion.hintFor(StampedDelta(GyroDelta(0.04f, 0f), 10L), Float.NaN))
        assertNull("confiance non finie", EgoMotion.hintFor(StampedDelta(GyroDelta(0.04f, 0f), 10L), FOCAL_PX, Float.NaN))
    }

    @Test
    fun `fusedSample - visuel utilisable, le gyro contradictoire est ignore`() {
        val visual = MotionSample(12f, 4f, 1f, CONFIDENT)
        assertEquals(visual, EgoMotion.fusedSample(visual, GyroHint(0f, 0f, CONFIDENT)))
    }

    @Test
    fun `fusedSample - fond muet, le gyro prend le relais`() {
        val sample = EgoMotion.fusedSample(null, GyroHint(12f, 4f, CONFIDENT))!!
        assertEquals(MotionSample(12f, 4f, 1f, CONFIDENT), sample)
    }

    @Test
    fun `fusedSample - fond peu confiant, le gyro prend le relais`() {
        val weak = MotionSample(12f, 4f, 1f, EgoMotion.TRUST_MIN - 0.01f)
        val sample = EgoMotion.fusedSample(weak, GyroHint(12f, 4f, CONFIDENT))!!
        assertEquals(MotionSample(12f, 4f, 1f, CONFIDENT), sample)
    }

    @Test
    fun `fusedSample - sans source, pas d avis`() {
        assertNull(EgoMotion.fusedSample(null, null))
        assertNull("gyro douteux", EgoMotion.fusedSample(null, GyroHint(12f, 4f, EgoMotion.TRUST_MIN - 0.01f)))
        assertNull("fond faible seul", EgoMotion.fusedSample(MotionSample(12f, 4f, 1f, 0.05f), null))
    }

    @Test
    fun `visualUsable - confiant et fini`() {
        assertTrue(EgoMotion.visualUsable(MotionSample(1f, 2f, 1f, CONFIDENT)))
        assertTrue("borne incluse", EgoMotion.visualUsable(MotionSample(0f, 0f, 1f, EgoMotion.TRUST_MIN)))
        assertFalse("confiance basse", EgoMotion.visualUsable(MotionSample(1f, 2f, 1f, 0.05f)))
        assertFalse("immobile sans confiance", EgoMotion.visualUsable(MotionSample.Zero))
        assertFalse("dx non fini", EgoMotion.visualUsable(MotionSample(Float.NaN, 0f, 1f, CONFIDENT)))
    }
}
