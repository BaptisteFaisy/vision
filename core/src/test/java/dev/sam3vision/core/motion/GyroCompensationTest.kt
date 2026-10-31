package dev.sam3vision.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PISTE 4 : la rotation caméra (Δyaw/Δpitch, radians) prédit un déplacement image
 * `dx ≈ f·Δyaw`, `dy ≈ f·Δpitch`, retranché du shift mesuré — sans capteur, sur
 * rotations synthétiques. Tout garde-fou qui cède rend `null` (incertain), jamais
 * un résidu inventé.
 */
class GyroCompensationTest {

    @Test
    fun `attendu nominal - f fois delta`() {
        val shift = GyroCompensation.expectedShift(GyroDelta(0.05f, -0.02f), FOCAL_PX)!!
        assertEquals(25f, shift.dxPx, EPSILON)
        assertEquals(-10f, shift.dyPx, EPSILON)
    }

    @Test
    fun `attendu - signes de la convention image`() {
        // Pan gauche (dYaw > 0) : scène vers la droite (dx > 0).
        // Tilt haut (dPitch > 0) : scène vers le bas (dy > 0).
        val right = GyroCompensation.expectedShift(GyroDelta(0.1f, 0f), FOCAL_PX)!!
        val down = GyroCompensation.expectedShift(GyroDelta(0f, 0.1f), FOCAL_PX)!!
        assertTrue(right.dxPx > 0f)
        assertTrue(down.dyPx > 0f)
    }

    @Test
    fun `attendu - nulle sans rotation`() {
        val shift = GyroCompensation.expectedShift(GyroDelta(0f, 0f), FOCAL_PX)!!
        assertEquals(0f, shift.dxPx, EPSILON)
        assertEquals(0f, shift.dyPx, EPSILON)
    }

    @Test
    fun `residu nul quand le mesure egale le gyro`() {
        val stamped = StampedDelta(GyroDelta(0.04f, 0.03f), AGE_FRESH_MS)
        val expected = GyroCompensation.expectedShift(stamped.delta, FOCAL_PX)!!
        val residual = GyroCompensation.residual(expected.dxPx, expected.dyPx, stamped, FOCAL_PX)!!
        assertEquals(0f, residual.dxPx, EPSILON)
        assertEquals(0f, residual.dyPx, EPSILON)
        assertEquals(0f, residual.magnitudePx, EPSILON)
    }

    @Test
    fun `residu - le mesure moins l attendu`() {
        val stamped = StampedDelta(GyroDelta(0.04f, 0f), AGE_FRESH_MS)
        val expected = GyroCompensation.expectedShift(stamped.delta, FOCAL_PX)!!
        val residual = GyroCompensation.residual(expected.dxPx + 3f, expected.dyPx - 2f, stamped, FOCAL_PX)!!
        assertEquals(3f, residual.dxPx, EPSILON)
        assertEquals(-2f, residual.dyPx, EPSILON)
    }

    @Test
    fun `rotation excessive - incertain, pas de verdict`() {
        val big = StampedDelta(GyroDelta(GyroCompensation.MAX_DELTA_RAD + 0.01f, 0f), AGE_FRESH_MS)
        assertNull(GyroCompensation.residual(10f, 0f, big, FOCAL_PX))
        val bigPitch = StampedDelta(GyroDelta(0f, -GyroCompensation.MAX_DELTA_RAD - 0.01f), AGE_FRESH_MS)
        assertNull(GyroCompensation.residual(0f, 10f, bigPitch, FOCAL_PX))
    }

    @Test
    fun `rotation limite - encore compensee`() {
        val limit = StampedDelta(GyroDelta(GyroCompensation.MAX_DELTA_RAD, 0f), AGE_FRESH_MS)
        val expected = GyroCompensation.expectedShift(limit.delta, FOCAL_PX)!!
        val residual = GyroCompensation.residual(expected.dxPx, expected.dyPx, limit, FOCAL_PX)!!
        assertEquals(0f, residual.magnitudePx, EPSILON)
    }

    @Test
    fun `delta perime - incertain`() {
        val stale = StampedDelta(GyroDelta(0.04f, 0f), GyroCompensation.MAX_AGE_MS + 1L)
        assertNull(GyroCompensation.residual(20f, 0f, stale, FOCAL_PX))
    }

    @Test
    fun `age negatif - incertain`() {
        val future = StampedDelta(GyroDelta(0.04f, 0f), -1L)
        assertNull(GyroCompensation.residual(20f, 0f, future, FOCAL_PX))
    }

    @Test
    fun `focale manquante - repli sans crash`() {
        val stamped = StampedDelta(GyroDelta(0.04f, 0f), AGE_FRESH_MS)
        assertNull(GyroCompensation.expectedShift(stamped.delta, 0f))
        assertNull(GyroCompensation.expectedShift(stamped.delta, -10f))
        assertNull(GyroCompensation.expectedShift(stamped.delta, Float.NaN))
        assertNull(GyroCompensation.residual(20f, 0f, stamped, 0f))
        assertNull(GyroCompensation.residual(20f, 0f, stamped, Float.NaN))
    }

    @Test
    fun `entrees non finies - incertain`() {
        val fresh = StampedDelta(GyroDelta(0.04f, 0f), AGE_FRESH_MS)
        assertNull(GyroCompensation.residual(Float.NaN, 0f, fresh, FOCAL_PX))
        val nan = StampedDelta(GyroDelta(Float.NaN, 0f), AGE_FRESH_MS)
        assertNull(GyroCompensation.expectedShift(nan.delta, FOCAL_PX))
        assertNull(GyroCompensation.residual(20f, 0f, nan, FOCAL_PX))
    }

    @Test
    fun `delta angulaire - deroule l azimut a pi`() {
        // Pan qui traverse le nord : −179° → +179° = −2°, pas +358°.
        val from = Math.toRadians(-179.0).toFloat()
        val to = Math.toRadians(179.0).toFloat()
        assertEquals(Math.toRadians(-2.0).toFloat(), GyroCompensation.shortestDelta(from, to), EPSILON)
        assertEquals(Math.toRadians(2.0).toFloat(), GyroCompensation.shortestDelta(to, from), EPSILON)
    }

    @Test
    fun `delta angulaire - petit ecart inchange`() {
        assertEquals(0.05f, GyroCompensation.shortestDelta(1f, 1.05f), EPSILON)
    }

    @Test
    fun `hint gyro - repli rotation pure ou null`() {
        val sample = GyroHint(12f, 4f, CONFIDENT).toSample()!!
        assertEquals(12f, sample.dx, EPSILON)
        assertEquals(4f, sample.dy, EPSILON)
        assertEquals(1f, sample.scale, EPSILON)
        assertNull(GyroHint(12f, 4f, GyroCompensation.TRUST_MIN - 0.01f).toSample())
        assertNull(GyroHint(Float.NaN, 4f, CONFIDENT).toSample())
        assertNull(GyroHint(12f, 4f, Float.NaN).toSample())
    }

    private companion object {
        /** Focale du plan luma 256 px : ordre de grandeur d'un téléphone (~70°). */
        const val FOCAL_PX = 500f
        const val AGE_FRESH_MS = 40L
        const val CONFIDENT = 0.9f
        const val EPSILON = 1e-4f
    }
}
