package dev.sam3vision.core.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinLiteDecodeTest {

    private fun logits(class1: FloatArray, plane: Int): FloatArray {
        val out = FloatArray(2 * plane)
        class1.copyInto(out, plane)
        return out
    }

    @Test
    fun `decode rend deux masques argmax`() {
        val plane = 8 // 4×2
        val drive = logits(floatArrayOf(2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f), plane)
        val lanes = logits(FloatArray(plane) { -1f }, plane)
        val (d, l) = TwinLiteDecode.decode(listOf(drive, lanes), 4, 2)!!
        assertEquals(1, d.bytes[0].toInt())
        assertTrue(d.bytes.drop(1).all { it.toInt() == 0 })
        assertTrue(l.bytes.all { it.toInt() == 0 })
    }

    @Test
    fun `decode refuse une forme inattendue`() {
        assertNull(TwinLiteDecode.decode(listOf(FloatArray(16)), 4, 2))
        assertNull(TwinLiteDecode.decode(listOf(FloatArray(16), FloatArray(3)), 4, 2))
        assertNull(TwinLiteDecode.decode(listOf(FloatArray(16), FloatArray(16)), 0, 2))
    }

    @Test
    fun `coverage compte l avant-plan`() {
        val mask = SegmentMask(4, 2, byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0))
        assertEquals(0.25f, mask.coverage(), 1e-6f)
        assertEquals(0f, SegmentMask(2, 2, ByteArray(4)).coverage(), 0f)
    }

    @Test
    fun `boundingBox cadre l avant-plan`() {
        val mask = SegmentMask(4, 2, byteArrayOf(0, 1, 0, 0, 0, 0, 1, 0))
        assertEquals(MaskBox(1, 0, 2, 1), mask.boundingBox())
        assertNull(SegmentMask(4, 2, ByteArray(8)).boundingBox())
    }

    @Test
    fun `toPrompt normalise en 0-1000`() {
        val full = SegmentMask(640, 360, ByteArray(640 * 360) { 1 })
        assertEquals(MaskPrompt(0, 0, 998, 997), full.toPrompt(1280, 720))
        assertNull(SegmentMask(4, 4, ByteArray(16)).toPrompt(100, 100))
    }

    @Test
    fun `fromName inconnu rend OFF`() {
        assertEquals(SegmentBackend.OFF, SegmentBackend.fromName("n importe quoi"))
        assertEquals(SegmentBackend.OFF, SegmentBackend.fromName(null))
        assertEquals(SegmentBackend.TWINLITE, SegmentBackend.fromName("twinlite"))
    }
}
