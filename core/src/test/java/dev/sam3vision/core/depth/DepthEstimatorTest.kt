package dev.sam3vision.core.depth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DepthEstimatorTest {

    @Test
    fun `fromName migre tout vers DA2, profondeur toujours active`() {
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("da2"))
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("MIDAS"))
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("Auto"))
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("OFF"))
        assertEquals(DepthBackend.DA2, DepthBackend.fromName("n importe quoi"))
        assertEquals(DepthBackend.DA2, DepthBackend.fromName(null))
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
