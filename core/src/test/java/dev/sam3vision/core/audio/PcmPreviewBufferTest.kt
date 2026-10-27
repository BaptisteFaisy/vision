package dev.sam3vision.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPreviewBufferTest {
    @Test
    fun `le tampon ne garde que le son recent et les copies restent independantes`() {
        val buffer = PcmPreviewBuffer(seconds = 1)
        val first = ByteArray(24_000) { 1 }
        val second = ByteArray(24_000) { 2 }
        buffer.append(first, first.size)
        val frozen = buffer.snapshot()
        buffer.append(second, second.size)
        assertArrayEquals(first, frozen.pcm)
        val expected = first.takeLast(8_000).toByteArray() + second
        assertArrayEquals(expected, buffer.snapshot().pcm)
        assertEquals(48_000L, buffer.snapshot().totalBytes)
    }

    @Test
    fun `le silence eteint la parole et reset ne garde aucun ancien texte audio`() {
        val buffer = PcmPreviewBuffer()
        assertFalse(buffer.snapshot().speaking)
        val voice = ByteArray(3_200) { if (it % 2 == 0) 0 else 8 }
        buffer.append(voice, voice.size)
        assertTrue(buffer.snapshot().speaking)
        val silence = ByteArray(32_000)
        buffer.append(silence, silence.size)
        assertFalse(buffer.snapshot().speaking)
        buffer.reset()
        assertEquals(0, buffer.snapshot().pcm.size)
        assertEquals(0L, buffer.snapshot().totalBytes)
    }
}
