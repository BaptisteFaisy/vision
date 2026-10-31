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
    fun `confiance d'un pixel sur la hauteur de boite`() {
        assertEquals(0.995f, ReadingConfidence.of(listOf(0f, 0f, 10f, 200f), 384, 3.3f, null), 0.001f)
        assertEquals(0.9f, ReadingConfidence.of(listOf(0f, 0f, 10f, 10f), 384, 33f, null), 0.001f)
        assertEquals(0f, ReadingConfidence.of(listOf(1f, 2f), 384, 3f, null), 0f)
        assertEquals(0f, ReadingConfidence.of(listOf(0f, 0f, 10f, 200f), 0, 3f, null), 0f)
    }

    @Test
    fun `accord des branches multiplie la confiance`() {
        val box = listOf(0f, 0f, 10f, 200f)
        val agreed = ReadingConfidence.of(box, 384, 3.0f, 3.0f)
        val coherent = ReadingConfidence.of(box, 384, 3.0f, 3.3f)
        val split = ReadingConfidence.of(box, 384, 3.0f, 6.0f)
        assertEquals(0.995f, agreed, 0.001f)
        assertTrue(coherent in 0f..agreed)
        assertEquals(0f, split, 0.001f)
    }
}
