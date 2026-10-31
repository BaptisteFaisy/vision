package dev.sam3vision.core.stairs

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marches téléphoniques : contraste doux (nez à ~50 niveaux sur fond uni,
 * comme après downsampling 256×103), pas le blanc-sur-noir de la webcam.
 * Le détecteur par défaut doit les compter ; le bruit reste négatif.
 */
class StairSoftContrastTest {

    /** 256×103, fond 110, six nez de 2 px à 60 dans la ROI (lignes 41..102). */
    private fun softStairs(): GrayMask {
        val width = 256
        val height = 103
        val pixels = ByteArray(width * height) { 110 }
        for (y in listOf(50, 58, 66, 74, 82, 90)) {
            for (dy in 0..1) {
                for (x in 0 until width) pixels[(y + dy) * width + x] = 60
            }
        }
        return GrayMask(width, height, pixels)
    }

    @Test
    fun `marches douces telephone = comptees`() {
        val signal = StairDetector.detect(softStairs())

        assertTrue(signal.detected)
        assertTrue(signal.lineCount >= 4)
    }

    @Test
    fun `bruit doux = toujours pas d escalier`() {
        val random = java.util.Random(7)
        val noisy = ByteArray(256 * 103) { (110 + random.nextInt(21) - 10).toByte() }

        assertFalse(StairDetector.detect(GrayMask(256, 103, noisy)).detected)
    }
}
