package dev.sam3vision.core.depth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorTest {

    @Test
    fun `fromName tolere l inconnu et la casse`() {
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("da2"))
        assertEquals(DepthBackend.MIDAS, DepthBackend.fromName("MIDAS"))
        assertEquals(DepthBackend.AUTO, DepthBackend.fromName("Auto"))
        assertEquals(DepthBackend.OFF, DepthBackend.fromName("n importe quoi"))
        assertEquals(DepthBackend.OFF, DepthBackend.fromName(null))
    }

    @Test
    fun `NoOp echoue proprement`() {
        val outcome = runBlocking {
            NoOpDepthEstimator.estimate(IntArray(4), 2, 2)
        }
        assertTrue(outcome.isFailure)
        assertEquals(DepthBackend.OFF, NoOpDepthEstimator.backend)
        NoOpDepthEstimator.close()
    }

    @Test
    fun `syntheticGradientArgb est deterministe et opaque`() {
        val first = syntheticGradientArgb(16, 16)
        val second = syntheticGradientArgb(16, 16)
        assertEquals(first.toList(), second.toList())
        assertEquals(256, first.size)
        assertTrue(first.all { (it ushr 24) == 0xFF })
        assertFalse(first.all { it == first[0] })
    }
}
