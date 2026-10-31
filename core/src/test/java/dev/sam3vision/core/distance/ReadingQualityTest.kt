package dev.sam3vision.core.distance

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Occlusion inter-boîtes et confiance d'une lecture. */
class ReadingQualityTest {

    private fun detection(label: String, score: Float, box: List<Float>): Detection =
        Detection(label, score, box, 512, 384)

    @Test
    fun `boite contenue dans une meilleure detection est masquee`() {
        val detections = listOf(
            detection("voiture", 0.95f, listOf(50f, 50f, 450f, 350f)),
            detection("personne", 0.60f, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(detections, 0))
        assertTrue(Occlusion.isOccluded(detections, 1))
    }

    @Test
    fun `scores egaux, rien n'est ecarte`() {
        val detections = listOf(
            detection("personne", 0.90f, listOf(50f, 50f, 450f, 350f)),
            detection("homme", 0.90f, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(detections, 0))
        assertFalse(Occlusion.isOccluded(detections, 1))
    }

    @Test
    fun `sous le seuil de contenance, la boite est gardee`() {
        val detections = listOf(
            detection("voiture", 0.95f, listOf(50f, 50f, 250f, 350f)),
            detection("personne", 0.60f, listOf(150f, 100f, 350f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(detections, 1))
    }

    @Test
    fun `index hors liste ou boite malformee, pas d'occlusion`() {
        val detections = listOf(detection("personne", 0.9f, listOf(100f, 100f, 200f, 300f)))
        assertFalse(Occlusion.isOccluded(detections, 3))
        assertFalse(Occlusion.isOccluded(emptyList(), 0))
        val broken = listOf(detection("personne", 0.9f, listOf(1f, 2f)))
        assertFalse(Occlusion.isOccluded(broken, 0))
    }

    @Test
    fun `masqueur moins bien score, la boite est gardee par prudence`() {
        val detections = listOf(
            detection("voiture", 0.60f, listOf(50f, 50f, 450f, 350f)),
            detection("personne", 0.90f, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(detections, 1))
    }

    @Test
    fun `scores non finis, on mesure plutot qu'ecarter`() {
        val nanScore = listOf(
            detection("voiture", 0.95f, listOf(50f, 50f, 450f, 350f)),
            detection("personne", Float.NaN, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(nanScore, 1))
        val nanRival = listOf(
            detection("voiture", Float.NaN, listOf(50f, 50f, 450f, 350f)),
            detection("personne", 0.60f, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(nanRival, 1))
    }

    @Test
    fun `seuil de contenance exact et candidat malforme`() {
        // 60 % pile : écartée (>=) ; candidat malformé : ignoré
        val exact = listOf(
            detection("voiture", 0.95f, listOf(0f, 0f, 60f, 100f)),
            detection("personne", 0.60f, listOf(0f, 0f, 100f, 100f)),
        )
        assertTrue(Occlusion.isOccluded(exact, 1))
        val brokenRival = listOf(
            detection("voiture", 0.95f, listOf(1f, 2f)),
            detection("personne", 0.60f, listOf(100f, 100f, 200f, 300f)),
        )
        assertFalse(Occlusion.isOccluded(brokenRival, 1))
    }

    @Test
    fun `confiance d'un pixel sur la hauteur de boite`() {
        assertEquals(0.995f, ReadingConfidence.of(listOf(0f, 0f, 10f, 200f), 3.3f, null), 0.001f)
        assertEquals(0.9f, ReadingConfidence.of(listOf(0f, 0f, 10f, 10f), 33f, null), 0.001f)
        assertEquals(0f, ReadingConfidence.of(listOf(1f, 2f), 3f, null), 0f)
        assertEquals(0f, ReadingConfidence.of(listOf(0f, 200f, 10f, 100f), 3f, null), 0f)
    }

    @Test
    fun `accord des branches multiplie la confiance`() {
        val box = listOf(0f, 0f, 10f, 200f)
        val agreed = ReadingConfidence.of(box, 3.0f, 3.0f)
        val coherent = ReadingConfidence.of(box, 3.0f, 3.3f)
        val split = ReadingConfidence.of(box, 3.0f, 6.0f)
        assertEquals(0.995f, agreed, 0.001f)
        assertTrue(coherent in 0f..agreed)
        assertEquals(0f, split, 0.001f)
    }

    @Test
    fun `poids sol plein de pres, plancher vers l'horizon`() {
        assertEquals(1f, GroundWeight.of(0.42f), 0.001f)
        assertEquals(1f, GroundWeight.of(0.2f), 0.001f)
        assertEquals(0.5f, GroundWeight.of(0.1f), 0.001f)
        assertEquals(0.25f, GroundWeight.of(0.02f), 0.001f)
        assertEquals(GroundWeight.MIN_WEIGHT, GroundWeight.of(0f), 0f)
        assertEquals(GroundWeight.MIN_WEIGHT, GroundWeight.of(Float.NaN), 0f)
    }
}
