package dev.sam3vision.core.ground

import dev.sam3vision.core.depth.DepthMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundPipelineTest {

    private val scale = 8f

    /** Mètres → proximité (`meters = scale / closeness`, 0 = invalide). */
    private fun closenessOf(meters: FloatArray): FloatArray =
        FloatArray(meters.size) { i ->
            val m = meters[i]
            if (!m.isFinite() || m <= 0f) 0f else scale / m
        }

    private fun analyzeMeters(meters: FloatArray, w: Int, h: Int): GroundResult {
        // Micro-bruit déterministe (±1 mm ≪ seuil inlier 4 cm) : une grille exacte rend
        // le tirage de triplets dégénéré (colinéaires rejetés), comme en RansacPlaneTest.
        val jittered = FloatArray(meters.size) { i ->
            val m = meters[i]
            if (!m.isFinite()) m
            else m + (((i * 2654435761L) % 1000).toFloat() / 1000f - 0.5f) * 0.002f
        }
        val depth = DepthMap(w, h, closenessOf(jittered))
        return GroundPipeline.analyze(depth, scale, SyntheticScene.intrinsics(w, h), 0f)
    }

    @Test
    fun `sol plan = trajectoire degagee, plan OK`() {
        val meters = SyntheticScene.flatFloorDepth()

        val got = analyzeMeters(meters, SyntheticScene.W, SyntheticScene.H)

        assertTrue(got.planeOk)
        assertTrue(got.summary.pathClear)
        assertTrue(got.regions.isEmpty())
    }

    @Test
    fun `boite devant = region obstacle, trajectoire non degagee`() {
        // 128×96 : le sol domine (~4800 px) comme en réel, la boîte (14×71 = 994 px
        // > minRegionPixels 500) reste devant le sol partout (sol 2,5–4,5 m, boîte 2 m).
        val w = 128
        val h = 96
        val meters = SyntheticScene.withBox(SyntheticScene.flatFloorDepth(w, h), w, h, 64, 77, 34, 104, 2f)

        val got = analyzeMeters(meters, w, h)

        assertTrue(got.planeOk)
        assertTrue(got.regions.isNotEmpty())
        assertFalse(got.summary.pathClear)
    }

    @Test
    fun `entree invalide = resultat sur, sans plan`() {
        val depth = DepthMap(4, 4, FloatArray(16))

        val got = GroundPipeline.analyze(depth, scale, SyntheticScene.intrinsics(4, 4), 0f)

        assertFalse(got.planeOk)
        assertTrue(got.summary.pathClear)
        assertTrue(got.regions.isEmpty())
        assertEquals("Chemin dégagé. Vous pouvez avancer.", got.report.message)
    }
}
