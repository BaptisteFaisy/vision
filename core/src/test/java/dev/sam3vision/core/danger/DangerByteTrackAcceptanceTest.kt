package dev.sam3vision.core.danger

import dev.sam3vision.core.model.Detection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Acceptation ByteTrack : la même séquence nominale que la référence
 * ultralytics (`/tmp/bytetrack-acc/bytetrack-ref.py`, ultralytics 8.4.192,
 * seuils du `bytetrack.yaml`) doit rendre les mêmes ids de pistes :
 * 2 marcheurs, 1 occlusion courte (B réactivée, id gardé), 1 apparition (C),
 * 1 faible qui monte (D confirmée en f6).
 *
 * Divergences connues (documentées, hors nominal) : égalités parfaites de
 * coût (le Hongrois maison et `lapjv` peuvent choisir deux optimums
 * différents de même coût total) et micro-écarts flottants (Double ici,
 * float64 là, conversions normalisé/pixels) — le nominal a des marges
 * larges, aucun cas limite n'y figure.
 */
class DangerByteTrackAcceptanceTest {

    private val width = 640
    private val height = 480

    /** (cx, cy, w, h, score) pixels → détection, comme la référence. */
    private fun det(cx: Float, cy: Float, w: Float, h: Float, score: Float): Detection =
        Detection("personne", score, listOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f), width, height)

    private val frames = listOf(
        listOf(det(130f, 200f, 60f, 140f, 0.90f), det(435f, 215f, 70f, 130f, 0.85f)),
        listOf(det(138f, 200f, 60f, 140f, 0.90f), det(429f, 215f, 70f, 130f, 0.85f)),
        listOf(det(146f, 200f, 60f, 140f, 0.90f), det(280f, 260f, 60f, 120f, 0.90f)),
        listOf(
            det(154f, 200f, 60f, 140f, 0.90f), det(423f, 215f, 70f, 130f, 0.80f),
            det(280f, 260f, 60f, 120f, 0.90f), det(520f, 350f, 55f, 110f, 0.15f),
        ),
        listOf(
            det(162f, 200f, 60f, 140f, 0.90f), det(417f, 215f, 70f, 130f, 0.85f),
            det(280f, 260f, 60f, 120f, 0.90f), det(520f, 350f, 55f, 110f, 0.50f),
        ),
        listOf(
            det(170f, 200f, 60f, 140f, 0.90f), det(411f, 215f, 70f, 130f, 0.85f),
            det(280f, 260f, 60f, 120f, 0.90f), det(520f, 350f, 55f, 110f, 0.60f),
        ),
    )

    /** Ids rendus par la référence, triés, frame par frame. */
    private val expected = listOf(
        listOf(1, 2),
        listOf(1, 2),
        listOf(1),
        listOf(1, 2, 3),
        listOf(1, 2, 3),
        listOf(1, 2, 3, 4),
    )

    @Test
    fun `les ids concordent avec la reference ultralytics sur le nominal`() {
        val tracker = PersonTracker()
        frames.forEachIndexed { index, detections ->
            val ids = tracker.update(detections, index * 100L).map { it.id }.sorted()
            assertEquals("frame ${index + 1}", expected[index], ids)
        }
    }
}
