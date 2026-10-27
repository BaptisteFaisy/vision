package dev.sam3vision.core.local

import dev.sam3vision.core.mask.MaskMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calque local (lane L13, agent D/u13) : la décision par frame.
 *
 * Le calque **ne propage rien** tant qu'un estimateur n'est pas branché — il doit le
 * dire (`NO_ESTIMATOR`), pas faire semblant. Les bornes sont celles du rapport `u13` :
 * keyframe de plus de 1 200 ms périmé, confiance 0,35, 3 échecs d'estimateur, budget de
 * peinture 4 ms, cadence 30 ips avec plancher 10 ips.
 */
class LocalOverlayEngineTest {

    private val luma = ByteArray(LumaPlane.SIZE) { 64 }

    /** Estimateur de courtoisie : un mouvement plausible, toujours fiable. */
    private val fiable = MotionSource { _, _ -> MaskMotion(2.0, 1.0, 1.0, 0.9) }

    private fun engine(
        source: MotionSource = fiable,
        config: LocalOverlayConfig = LocalOverlayConfig(),
    ) = LocalOverlayEngine(config, source)

    @Test
    fun `peint quand tout est bon`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertTrue("le calque doit peindre", tick.paint)
        assertEquals(LocalReason.PAINT, tick.reason)
        assertNotNull("le mouvement estimé est transmis", tick.motion)
    }

    @Test
    fun `refuse quand le calque est eteint`() {
        val tick = engine(config = LocalOverlayConfig(enabled = false))
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.DISABLED, tick.reason)
    }

    @Test
    fun `refuse tant qu aucun keyframe n est arrive`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = -1L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.NO_KEYFRAME, tick.reason)
    }

    @Test
    fun `un keyframe perime n est plus propage`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = 1_201L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.STALE_KEYFRAME, tick.reason)
    }

    @Test
    fun `un keyframe a la limite d age est encore propage`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = 1_200L, confidence = 0.9f)

        assertTrue("1 200 ms est la borne, pas l'exclusion", tick.paint)
    }

    @Test
    fun `une confiance trop basse ne propage rien`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.34f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.LOW_CONFIDENCE, tick.reason)
    }

    @Test
    fun `la confiance minimale est acceptee`() {
        val tick = engine().tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.35f)

        assertTrue(tick.paint)
    }

    @Test
    fun `sans estimateur le calque est inerte`() {
        val tick = engine(source = NoMotion).tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.NO_ESTIMATOR, tick.reason)
    }

    @Test
    fun `sans luminance le calque est inerte`() {
        val tick = engine().tick(0L, null, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.NO_ESTIMATOR, tick.reason)
    }

    @Test
    fun `une estimation absente compte comme un echec`() {
        val tick = engine(source = MotionSource { _, _ -> null })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.ESTIMATOR_FAILED, tick.reason)
    }

    @Test
    fun `une exception de l estimateur n arrete pas le direct`() {
        val tick = engine(source = MotionSource { _, _ -> error("boom") })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.ESTIMATOR_FAILED, tick.reason)
    }

    @Test
    fun `l estimateur est abandonne apres trois echecs`() {
        var appels = 0
        val calque = engine(source = MotionSource { _, _ -> appels++; null })

        repeat(3) { calque.tick(it * 1_000L, luma, keyframeAgeMs = 100L, confidence = 0.9f) }
        calque.tick(4_000L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertEquals("l'estimateur ne doit plus être appelé", 3, appels)
    }

    @Test
    fun `un nouveau keyframe remet les echecs a zero`() {
        var appels = 0
        val calque = engine(source = MotionSource { _, _ -> appels++; null })

        repeat(3) { calque.tick(it * 1_000L, luma, keyframeAgeMs = 100L, confidence = 0.9f) }
        calque.onKeyframe(luma)
        calque.tick(4_000L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertEquals("l'estimateur est appelé de nouveau", 4, appels)
    }

    @Test
    fun `la cadence refuse une frame arrivee trop tot`() {
        val calque = engine()

        assertTrue(calque.tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f).paint)
        val tropTot = calque.tick(10L, luma, keyframeAgeMs = 110L, confidence = 0.9f)

        assertFalse(tropTot.paint)
        assertEquals(LocalReason.TOO_EARLY, tropTot.reason)
    }

    @Test
    fun `la cadence est divisee par deux quand la peinture depasse le budget`() {
        val calque = engine()

        calque.notePaint(5_000L)
        calque.notePaint(5_000L)

        assertEquals("66 ms = 33 ms × 2", 66L, calque.tick(0L, luma, 100L, 0.9f).intervalMs)
    }

    @Test
    fun `la cadence ne descend pas sous le plancher minFps`() {
        val calque = engine()

        repeat(10) { calque.notePaint(50_000L) }

        assertEquals("100 ms = 10 ips", 100L, calque.tick(0L, luma, 100L, 0.9f).intervalMs)
    }

    @Test
    fun `la cadence remonte quand la peinture redevient rapide`() {
        val calque = engine()

        repeat(2) { calque.notePaint(5_000L) }
        repeat(10) { calque.notePaint(100L) }

        assertEquals("33 ms = 30 ips", 33L, calque.tick(0L, luma, 100L, 0.9f).intervalMs)
    }

    @Test
    fun `un mouvement hors bornes compte comme un echec`() {
        val tick = engine(source = MotionSource { _, _ -> MaskMotion(0.0, 0.0, 7.0, 1.0) })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.ESTIMATOR_FAILED, tick.reason)
    }

    @Test
    fun `un mouvement qui diverge compte comme un echec`() {
        val tick = engine(source = MotionSource { _, _ -> MaskMotion(Double.NaN, 0.0, 1.0, 1.0) })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertEquals(LocalReason.ESTIMATOR_FAILED, tick.reason)
    }

    @Test
    fun `un mouvement de confiance faible n est pas propage`() {
        val tick = engine(source = MotionSource { _, _ -> MaskMotion(1.0, 1.0, 1.0, 0.2) })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)

        assertFalse(tick.paint)
        assertEquals(LocalReason.LOW_CONFIDENCE, tick.reason)
    }

    @Test
    fun `sans mouvement le tick reste peint au centre`() {
        val tick = engine(source = MotionSource { _, _ -> MaskMotion() })
            .tick(0L, luma, keyframeAgeMs = 100L, confidence = 0.9f)
        val mouvement = tick.motion ?: error("le mouvement doit accompagner le tick")

        assertTrue(tick.paint)
        assertEquals(0.0, mouvement.dx, 0.001)
        assertEquals(1.0, mouvement.scale, 0.001)
    }

    @Test
    fun `reset remet la cadence et les echecs a zero`() {
        val calque = engine()
        repeat(2) { calque.notePaint(5_000L) }
        calque.tick(0L, luma, 100L, 0.9f)

        calque.reset()

        assertEquals(33L, calque.tick(0L, luma, 100L, 0.9f).intervalMs)
    }

    @Test
    fun `setMotion remplace l estimateur`() {
        val calque = engine(source = NoMotion)
        assertEquals(LocalReason.NO_ESTIMATOR, calque.tick(0L, luma, 100L, 0.9f).reason)

        calque.setMotion(fiable)

        assertTrue(calque.tick(1_000L, luma, 100L, 0.9f).paint)
    }

    @Test
    fun `une configuration hors bornes est normalisee`() {
        val config = LocalOverlayConfig(
            targetFps = 500,
            minFps = 0,
            minConfidence = 4f,
            maxFailures = 0,
        ).normalized()

        assertEquals(LocalOverlayConfig.MAX_FPS, config.targetFps)
        assertEquals(LocalOverlayConfig.MIN_FPS, config.minFps)
        assertEquals(1f, config.minConfidence, 0.001f)
        assertEquals(1, config.maxFailures)
    }
}