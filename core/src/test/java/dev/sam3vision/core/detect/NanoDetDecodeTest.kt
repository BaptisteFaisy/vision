package dev.sam3vision.core.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NanoDetDecodeTest {

    private fun blank(): Array<FloatArray> =
        Array(2125) { FloatArray(NanoDetDecode.ROW) }

    /** Pic à la ligne [row] : [score] sur [label], DFL one-hot au palier 4. */
    private fun peak(feats: Array<FloatArray>, row: Int, label: Int, score: Float) {
        feats[row][label] = score
        for (side in 0 until 4) {
            feats[row][NanoDetDecode.NUM_CLASS + side * 8 + 4] = 10f
        }
    }

    @Test
    fun `priors compte 2125 dans l ordre des pas`() {
        val priors = NanoDetDecode.priors()
        assertEquals(2125, priors.size)
        assertEquals(NanoDetDecode.Prior(0, 0, 8), priors.first())
        assertEquals(NanoDetDecode.Prior(0, 0, 16), priors[1600])
        assertEquals(NanoDetDecode.Prior(4, 4, 64), priors.last())
    }

    @Test
    fun `detect vide rend vide`() {
        assertTrue(NanoDetDecode.detect(blank(), 640, 640).isEmpty())
    }

    @Test
    fun `detect ignore une forme inattendue`() {
        assertTrue(NanoDetDecode.detect(Array(10) { FloatArray(112) }, 64, 64).isEmpty())
        assertTrue(NanoDetDecode.detect(Array(2125) { FloatArray(3) }, 64, 64).isEmpty())
    }

    @Test
    fun `detect decode un pic en boite`() {
        // Ligne 410 = pas 8, (x=10, y=10) → centre (80, 80), ±4×8 px.
        val feats = blank()
        peak(feats, 410, 0, 0.9f)
        val boxes = NanoDetDecode.detect(feats, 640, 640)
        assertEquals(1, boxes.size)
        val box = boxes.single()
        assertEquals(96f, box.x1, 0.5f)
        assertEquals(96f, box.y1, 0.5f)
        assertEquals(224f, box.x2, 0.5f)
        assertEquals(224f, box.y2, 0.5f)
        assertEquals(0, box.label)
        assertEquals("person", box.labelName)
        assertEquals(0.9f, box.score, 1e-6f)
    }

    @Test
    fun `nms garde le meilleur des chevauchants`() {
        val feats = blank()
        peak(feats, 410, 0, 0.9f)
        peak(feats, 411, 0, 0.8f)
        val boxes = NanoDetDecode.detect(feats, 640, 640)
        assertEquals(1, boxes.size)
        assertEquals(0.9f, boxes.single().score, 1e-6f)
    }

    @Test
    fun `nms garde les classes differentes`() {
        val feats = blank()
        peak(feats, 410, 0, 0.9f)
        peak(feats, 411, 2, 0.8f)
        assertEquals(2, NanoDetDecode.detect(feats, 640, 640).size)
    }

    @Test
    fun `toSamPrompt normalise et borne`() {
        val box = DetectBox(-10f, -5f, 700f, 900f, 0, "person", 0.9f)
        assertEquals(SamBoxPrompt(0, 0, 1000, 1000), box.toSamPrompt(640, 480))
        val inner = DetectBox(64f, 48f, 128f, 96f, 2, "car", 0.5f)
        assertEquals(SamBoxPrompt(100, 100, 200, 200), inner.toSamPrompt(640, 480))
    }

    @Test
    fun `fromName inconnu rend OFF`() {
        assertEquals(DetectBackend.OFF, DetectBackend.fromName("n importe quoi"))
        assertEquals(DetectBackend.OFF, DetectBackend.fromName(null))
        assertEquals(DetectBackend.NANODET, DetectBackend.fromName("nanodet"))
    }
}
