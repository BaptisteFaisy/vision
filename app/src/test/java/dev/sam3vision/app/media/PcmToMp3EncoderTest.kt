package dev.sam3vision.app.media

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Régression : l'encodeur doit produire un **vrai** MP3 (et non lever sur le mode
 * de canal). jump3r 1.0.4 rejette tout mode explicite (« No enum constant
 * MPEGMode.mono ») ; ce test tombe si on y revient.
 */
class PcmToMp3EncoderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** PCM mono 16 bits 16 kHz : un demi-ton à 440 Hz pendant [ms] millisecondes. */
    private fun tonePcm(ms: Int, rate: Int = 16_000): ByteArray {
        val samples = rate * ms / 1000
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (Math.sin(2 * Math.PI * 440.0 * i / rate) * 8_000).toInt()
            pcm[2 * i] = (value and 0xff).toByte()
            pcm[2 * i + 1] = ((value shr 8) and 0xff).toByte()
        }
        return pcm
    }

    @Test
    fun `encode un PCM mono en MP3 mono 16 kHz`() {
        val out = File(temp.root, "user-test.mp3")
        PcmToMp3Encoder().encode(tonePcm(ms = 500), out)

        val bytes = out.readBytes()
        assertTrue("fichier MP3 non vide", bytes.size > 1_000)

        // Trame MPEG : 0xFF 0xEx ; version 2 (16 kHz), mode canal 3 = mono.
        val header = bytes.size - 4
        var frame = 0
        while (frame < header && !(bytes[frame] == 0xFF.toByte() && (bytes[frame + 1].toInt() and 0xE0) == 0xE0)) {
            frame++
        }
        assertTrue("aucune trame MPEG trouvée", frame < header)
        val version = (bytes[frame + 1].toInt() shr 3) and 0x3
        val sampleRateIndex = (bytes[frame + 2].toInt() shr 2) and 0x3
        val channelMode = (bytes[frame + 3].toInt() shr 6) and 0x3
        assertEquals("MPEG-2 (16 kHz)", 2, version)
        assertEquals("index de fréquence 16 kHz", 2, sampleRateIndex)
        assertEquals("canal mono", 3, channelMode)
    }
}
