package dev.sam3vision.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modèle de mouvement : extrapolation, garde-fou de dérive, confiance. */
class MotionModelTest {

    private val maxSpeed = 512f * 0.9f

    private fun movingBox(centerX: Float) = Box(centerX - 20f, 100f, centerX + 20f, 180f)

    @Test
    fun `extrapole une vitesse constante apres plusieurs observations`() {
        val model = MotionModel(movingBox(100f), 0L)
        for (i in 1..4) {
            model.observe(movingBox(100f + 20f * i), 200L * i, maxSpeed)
        }
        val lastServer = 100f + 20f * 4
        val predicted = model.predictAt(900L)
        assertTrue(predicted.centerX > lastServer)
        assertTrue(predicted.centerX <= lastServer + 12f)
        assertEquals(0, model.jumps)
    }

    @Test
    fun `un ecart faible ne fait pas sauter l'ancre`() {
        val model = MotionModel(movingBox(100f), 0L)
        assertEquals(MotionModel.Outcome.FOLLOWED, model.observe(movingBox(102f), 200L, maxSpeed))
        assertEquals(0, model.jumps)
    }

    @Test
    fun `une boite serveur ailleurs re-ancre sans lisser`() {
        val model = MotionModel(movingBox(100f), 0L)
        val moved = movingBox(300f)
        assertEquals(MotionModel.Outcome.JUMPED, model.observe(moved, 200L, maxSpeed))
        assertEquals(1, model.jumps)
        assertEquals(moved.centerX, model.anchorBox.centerX, 0.01f)
    }

    @Test
    fun `une observation a l'instant de l'ancre ne touche pas la vitesse`() {
        val model = MotionModel(movingBox(100f), 0L)
        model.observe(movingBox(120f), 200L, maxSpeed)
        val speedBefore = model.velocity.x
        assertEquals(MotionModel.Outcome.STALE, model.observe(movingBox(120f), 200L, maxSpeed))
        assertEquals(speedBefore, model.velocity.x, 0.01f)
    }

    @Test
    fun `la confiance decroit puis tombe a zero`() {
        val model = MotionModel(movingBox(100f), 0L)
        model.observe(movingBox(105f), 100L, maxSpeed)
        model.observe(movingBox(110f), 200L, maxSpeed)
        assertEquals(1f, model.confidenceAt(200L), 1e-3f)
        assertTrue(model.confidenceAt(400L) < 0.6f)
        assertEquals(0f, model.confidenceAt(200L + MotionModel.CONFIDENCE_ZERO_MS), 1e-3f)
    }

    @Test
    fun `un objet vu une seule fois a une vitesse non fiable mais un masque frais`() {
        val model = MotionModel(movingBox(100f), 0L)
        assertFalse(model.velocityReliable)
        assertEquals(1f, model.confidenceAt(0L), 1e-3f)
        model.observe(movingBox(105f), 100L, maxSpeed)
        model.observe(movingBox(110f), 200L, maxSpeed)
        assertTrue(model.velocityReliable)
    }

    @Test
    fun `l'horizon d'extrapolation est borne`() {
        val model = MotionModel(movingBox(100f), 0L)
        for (i in 1..4) model.observe(movingBox(100f + 40f * i), 200L * i, maxSpeed)
        val far = model.predictAt(200L * 4 + 5_000L)
        val horizon = model.predictAt(200L * 4 + MotionModel.MAX_HORIZON_MS)
        assertEquals(horizon.centerX, far.centerX, 0.01f)
    }
}