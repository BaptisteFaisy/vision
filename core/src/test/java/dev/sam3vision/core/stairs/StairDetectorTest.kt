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
    fun `nez epais = une marche par bande, pas deux`() {
        // Régression 31/10 : un nez clair de 5-6 px donnait deux contours
        // (haut + bas de bande) comptés deux marches — 5 marches vues comme
        // 10. Seul le côté montant compte : une bande = une marche.
        assertEquals(5, StairDetector.detect(phoneStairs(5)).lineCount)
        assertEquals(5, StairDetector.detect(phoneStairs(6)).lineCount)
    }

    /** Plan téléphone 256×103 : 5 nez clairs épais sur fond sombre. */
    private fun phoneStairs(thickness: Int): GrayMask {
        val width = 256
        val height = 103
        val pixels = ByteArray(width * height) { 40 }
        for (start in listOf(48, 58, 68, 78, 88)) {
            for (y in start until (start + thickness).coerceAtMost(height)) {
                for (x in 0 until width) pixels[y * width + x] = 200.toByte()
            }
        }
        return GrayMask(width, height, pixels)
    }

    @Test
    fun `roi sam = marches dedans comptees, dehors ignorees`() {
        // 24,25 dans le bas fixe mais hors ROI (top 0,5) ; 36,44,52,58 dedans.
        val mask = stairsLike(24, 25, 36, 44, 52, 58)
        val full = StairDetector.detect(mask)
        val bounded = StairDetector.detect(mask, roi = StairRoi(0f, 0.5f, 1f, 1f))

        assertEquals(5, full.lineCount)
        assertEquals(4, bounded.lineCount)
        assertTrue(bounded.detected)
        assertEquals(30, bounded.roiTop)
        assertEquals(60, bounded.roiBottom)
    }

    @Test
    fun `roi etroite = couverture proportionnee a la largeur`() {
        // Marches sur le tiers central seul : invisibles à l'échelle image,
        // comptées à l'échelle de la boîte SAM.
        val width = 64
        val height = 60
        val pixels = ByteArray(width * height) { 20 }
        for (y in listOf(30, 38, 46, 54)) {
            for (x in 24 until 40) pixels[y * width + x] = 255.toByte()
        }
        val mask = GrayMask(width, height, pixels)
        val full = StairDetector.detect(mask)
        val bounded = StairDetector.detect(mask, roi = StairRoi(24f / 64, 0f, 40f / 64, 1f))

        assertEquals(0, full.lineCount)
        assertEquals(4, bounded.lineCount)
        assertTrue(bounded.detected)
    }

    @Test
    fun `roi degeneree = signal vide et coordonnees triees`() {
        val mask = stairsLike(30, 38, 46, 54)
        assertEquals(StairSignal.none(), StairDetector.detect(mask, roi = StairRoi(0.5f, 0.5f, 0.5f, 0.5f)))
        assertEquals(StairRoi(0.2f, 0.2f, 0.8f, 0.8f), StairRoi(0.8f, 0.8f, 0.2f, 0.2f).normalized())
        assertEquals(null, StairRoi(2f, 2f, 3f, 3f).normalized())
    }

    @Test
    fun `masque sam = marches dedans comptees, dehors ignorees`() {
        // Masque = moitié basse (lignes 30..59) : 24,25 ignorées, 36,44,52,58 comptées.
        val plane = ByteArray(64 * 60) { i -> if (i / 64 >= 30) 1 else 0 }
        val signal = StairDetector.detect(stairsLike(24, 25, 36, 44, 52, 58), maskPlane = plane)

        assertEquals(4, signal.lineCount)
        assertTrue(signal.detected)
        assertEquals(30, signal.roiTop)
        assertEquals(60, signal.roiBottom)
    }

    @Test
    fun `masque haut = escalier cadre en haut compte`() {
        // Le cas que le bas fixe rate : 3 marches sur 4 au-dessus de la ligne 24.
        val plane = ByteArray(64 * 60) { i -> if (i / 64 < 30) 1 else 0 }
        val mask = stairsLike(4, 12, 20, 28)
        val full = StairDetector.detect(mask)
        val bounded = StairDetector.detect(mask, maskPlane = plane)

        assertEquals(1, full.lineCount)
        assertEquals(4, bounded.lineCount)
        assertTrue(bounded.detected)
        assertEquals(0, bounded.roiTop)
        assertEquals(30, bounded.roiBottom)
    }

    @Test
    fun `masque prime sur roi`() {
        // ROI = moitié haute (0 marche dedans), masque = image entière (4 dedans).
        val plane = ByteArray(64 * 60) { 1 }
        val mask = stairsLike(36, 44, 52, 58)
        val roiOnly = StairDetector.detect(mask, roi = StairRoi(0f, 0f, 1f, 0.5f))
        val masked = StairDetector.detect(mask, roi = StairRoi(0f, 0f, 1f, 0.5f), maskPlane = plane)

        assertEquals(0, roiOnly.lineCount)
        assertEquals(4, masked.lineCount)
    }

    @Test
    fun `plan vide ou incoherent = repli sur`() {
        val mask = stairsLike(36, 44, 52, 58)

        assertEquals(StairSignal.none(), StairDetector.detect(mask, maskPlane = ByteArray(64 * 60)))
        assertEquals(4, StairDetector.detect(mask, maskPlane = ByteArray(10)).lineCount)
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
