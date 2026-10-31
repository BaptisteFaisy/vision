package dev.sam3vision.core.traverse

import dev.sam3vision.core.model.SegObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thread `SegObject.depth` du pipeline (PISTE2) : les proximités serveur
 * atteignent le veto suivi — à profondeur constante le tau se tait, sans
 * profondeur il parle comme avant. Première frame cachée par l'hystérésis,
 * tau établi à la quatrième. Focale ≤ 0 : pas de distance.
 */
class TraversePipelineDepthTest {

    private fun seg(widthPx: Float, depth: Float?): SegObject =
        SegObject("car", 0.9f, box(widthPx), "", depth = depth)

    private fun box(widthPx: Float): List<Float> {
        val heightPx = widthPx * 0.6f
        return listOf(320f - widthPx / 2f, 240f - heightPx / 2f, 320f + widthPx / 2f, 240f + heightPx / 2f)
    }

    /** Quatre frames croissantes (100→130), mesurées à [depth] ; verdict de la dernière. */
    private suspend fun lastVerdict(depth: Float?): TraverseDecision {
        val pipeline = TraversePipeline()
        var decision: TraverseDecision? = null
        listOf(100f, 110f, 120f, 130f).forEachIndexed { index, width ->
            val out = pipeline.onFrame(listOf(seg(width, depth)), 640, 480, true, -1f, index * 100L)
            decision = out.decision
        }
        return decision!!
    }

    @Test
    fun `depth serveur constante - le tau du pipeline se tait`() = runBlocking {
        val decision = lastVerdict(0.5f)

        assertTrue("croissance deux frames : l'instantané bloque", decision.veto)
        assertNull("tau suivi invalidé par le pallier serveur", decision.approachTtcSeconds)
    }

    @Test
    fun `sans depth serveur - le tau du pipeline parle (non-regression)`() = runBlocking {
        val decision = lastVerdict(null)

        assertTrue(decision.veto)
        assertNotNull(decision.approachTtcSeconds)
    }
}
