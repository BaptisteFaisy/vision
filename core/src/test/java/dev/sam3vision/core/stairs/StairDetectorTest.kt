package dev.sam3vision.core.stairs

import dev.sam3vision.core.mask.GrayMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StairDetectorTest {

    private val config = StairsConfig()

    private fun stairsLike(vararg rows: Int): GrayMask {
        val width = 64
        val height = 60
        val pixels = ByteArray(width * height) { 20 }
        for (y in rows) {
            for (x in 0 until width) pixels[y * width + x] = 255.toByte()
        }
        return GrayMask(width, height, pixels)
    }

    @Test
    fun `cinq marches dans la ROI = escalier`() {
        val signal = StairDetector.detect(stairsLike(30, 38, 46, 52, 57))

        assertTrue(signal.detected)
        assertTrue(signal.lineCount >= 4)
        assertTrue(signal.confidence > 0f)
        assertEquals(24, signal.roiTop)
        assertEquals(60, signal.roiBottom)
    }

    @Test
    fun `quatre marches = seuil detecte`() {
        val signal = StairDetector.detect(stairsLike(30, 38, 46, 54))

        assertTrue(signal.detected)
    }

    @Test
    fun `trois marches = pas d escalier`() {
        val signal = StairDetector.detect(stairsLike(30, 40, 50))

        assertFalse(signal.detected)
        assertEquals(0f, signal.confidence, 0f)
    }

    @Test
    fun `marches hors ROI = pas d escalier`() {
        val signal = StairDetector.detect(stairsLike(4, 8, 12, 16, 20))

        assertFalse(signal.detected)
        assertEquals(0, signal.lineCount)
    }

    @Test
    fun `rayures verticales = pas d escalier`() {
        val width = 64
        val height = 60
        val pixels = ByteArray(width * height) { 20 }
        for (stripe in 0 until width step 8) {
            for (y in 0 until height) pixels[y * width + stripe] = 255.toByte()
        }
        val signal = StairDetector.detect(GrayMask(width, height, pixels))

        assertFalse(signal.detected)
        assertEquals(0, signal.lineCount)
    }

    @Test
    fun `store a deux lames = pas d escalier`() {
        // Deux lames de store vénitien : sous le seuil, donc négatif. Un store
        // dense (≥ 4 lames) reste un faux positif connu (limite MIT documentée).
        val signal = StairDetector.detect(stairsLike(32, 48))

        assertFalse(signal.detected)
    }

    @Test
    fun `diagonales = pas d escalier`() {
        val width = 64
        val height = 60
        val pixels = ByteArray(width * height) { 20 }
        for (y in 0 until height) {
            for (line in 0 until 4) {
                val x = (y + line * 16) % width
                pixels[y * width + x] = 255.toByte()
            }
        }
        val signal = StairDetector.detect(GrayMask(width, height, pixels))

        assertFalse(signal.detected)
    }

    @Test
    fun `aplat et bruit capteur = pas d escalier`() {
        assertFalse(StairDetector.detect(GrayMask(64, 60, ByteArray(64 * 60) { 128.toByte() })).detected)
        val random = java.util.Random(11)
        val noisy = ByteArray(64 * 60) { (128 + random.nextInt(21) - 10).toByte() }
        assertFalse(StairDetector.detect(GrayMask(64, 60, noisy)).detected)
    }

    @Test
    fun `image inexploitable = signal vide sans exception`() {
        assertEquals(StairSignal.none(), StairDetector.detect(GrayMask(0, 0, ByteArray(0))))
        assertEquals(StairSignal.none(), StairDetector.detect(GrayMask(4, 4, ByteArray(3))))
        val tiny = StairDetector.detect(GrayMask(8, 8, ByteArray(64)))
        assertFalse(tiny.detected)
        assertEquals(0, tiny.lineCount)
    }

    @Test
    fun `seuils invalides = normalises sans exception`() {
        val wild = StairsConfig(cannyLow = 900, cannyHigh = -5, minLines = 0, minRowCoverage = 99f)
        val signal = StairDetector.detect(stairsLike(30, 38, 46, 52, 57), wild)

        assertTrue(signal.lineCount >= 0)
    }

    @Test
    fun `groupement fusionne les lignes voisines et coupe au-dela du trou`() {
        val width = 64
        // Lignes actives pleines aux rangs 0,1 puis 4 : trou de 2 (maxRowGap) = une seule marche.
        val merged = ByteArray(64 * 8)
        for (y in listOf(0, 1, 4)) {
            for (x in 0 until width) merged[y * width + x] = 1
        }
        assertEquals(1, StairDetector.countLines(merged, width, 0, 8, config))
        // Mêmes lignes avec un trou de 3 : deux marches.
        val split = ByteArray(64 * 8)
        for (y in listOf(0, 1, 5)) {
            for (x in 0 until width) split[y * width + x] = 1
        }
        assertEquals(2, StairDetector.countLines(split, width, 0, 8, config))
    }

    @Test
    fun `groupement exige la couverture minimale`() {
        val width = 64
        val edges = ByteArray(64 * 8)
        for (y in 0 until 8) {
            for (x in 0 until 5) edges[y * width + x] = 1
        }
        assertEquals(0, StairDetector.countLines(edges, width, 0, 8, config))
    }

    @Test
    fun `confiance en rampe 0 puis tiers`() {
        assertEquals(0f, StairDetector.confidenceFor(0, 4), 0f)
        assertEquals(0f, StairDetector.confidenceFor(3, 4), 0f)
        assertEquals(1f / 3f, StairDetector.confidenceFor(4, 4), 1e-6f)
        assertEquals(2f / 3f, StairDetector.confidenceFor(5, 4), 1e-6f)
        assertEquals(1f, StairDetector.confidenceFor(6, 4), 0f)
        assertEquals(1f, StairDetector.confidenceFor(20, 4), 0f)
    }
}
