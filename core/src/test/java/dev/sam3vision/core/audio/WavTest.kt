package dev.sam3vision.core.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

private fun ByteArray.ascii(from: Int, length: Int) = String(this, from, length, Charsets.US_ASCII)
private fun ByteArray.intLe(from: Int) =
    (0 until 4).sumOf { i -> (this[from + i].toInt() and 0xFF) shl (8 * i) }

class WavTest {

    @Test
    fun `l'en-tete annonce RIFF WAVE PCM 16 bits mono 16 kHz`() {
        val pcmBytes = 32_000
        val header = Wav.header(pcmBytes)
        assertEquals(44, header.size)
        assertEquals("RIFF", header.ascii(0, 4))
        assertEquals("WAVE", header.ascii(8, 4))
        assertEquals("fmt ", header.ascii(12, 4))
        assertEquals("data", header.ascii(36, 4))
        assertEquals(36 + pcmBytes, header.intLe(4))
        assertEquals(16_000, header.intLe(24))
        assertEquals(32_000, header.intLe(28))
        assertEquals(1, header[22].toInt())
        assertEquals(2, header[32].toInt())
        assertEquals(16, header[34].toInt())
        assertEquals(pcmBytes, header.intLe(40))
    }

    @Test
    fun `pcm16 colle l'en-tete devant les octets bruts`() {
        val pcm = ByteArray(640) { i -> (i % 120).toByte() }
        val wav = Wav.pcm16(pcm)
        assertEquals(44 + pcm.size, wav.size)
        assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
        assertEquals(640, wav.intLe(40))
    }

    @Test
    fun `la duree se deduit du nombre d'octets`() {
        assertEquals(0L, Wav.durationMs(0))
        assertEquals(20L, Wav.durationMs(640))
        assertEquals(1_000L, Wav.durationMs(32_000))
        assertEquals(60_000L, Wav.durationMs(1_920_000))
    }
}