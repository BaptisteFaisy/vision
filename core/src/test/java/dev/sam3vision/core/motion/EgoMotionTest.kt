package dev.sam3vision.core.motion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fond visuel utilisable : confiance et valeurs finies — sans source
 * utilisable, le doute ne parle jamais. (Le gyro a été supprimé sur ordre
 * user du 02/11 : verdicts visuels seuls.)
 */
class EgoMotionTest {

    companion object {
        private const val CONFIDENT = 0.9f
    }

    @Test
    fun `visualUsable - confiant et fini`() {
        assertTrue(EgoMotion.visualUsable(MotionSample(1f, 2f, 1f, CONFIDENT)))
        assertTrue("borne incluse", EgoMotion.visualUsable(MotionSample(0f, 0f, 1f, EgoMotion.TRUST_MIN)))
        assertFalse("confiance basse", EgoMotion.visualUsable(MotionSample(1f, 2f, 1f, 0.05f)))
        assertTrue("zéro prouvé : frames identiques, confiance pleine", EgoMotion.visualUsable(MotionSample.Zero))
        assertFalse("dx non fini", EgoMotion.visualUsable(MotionSample(Float.NaN, 0f, 1f, CONFIDENT)))
    }
}
