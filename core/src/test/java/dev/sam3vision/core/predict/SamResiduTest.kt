package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.MaskFixtures
import dev.sam3vision.core.motion.BlockMotionSource
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.motion.MotionScenes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Résidu SAM ([SamResidu.decide]) : le masque keyframe compensé de l'ego-motion
 * coïncide-t-il avec le masque courant ? Masques rectangulaires synthétiques sur la
 * grille 256×103 (mêmes dimensions que [MotionScenes]), IoU calculables à la main :
 * objet 64×40 déplacé de 16 px ⇒ IoU 0,60 (mobile), érosion 1 px ⇒ 0,92 (bruit).
 */
class SamResiduTest {

    private fun mask(box: Box): GrayMask = MaskFixtures.rect(
        MotionScenes.WIDTH,
        MotionScenes.HEIGHT,
        box.x1.toInt(),
        box.y1.toInt(),
        box.x2.toInt(),
        box.y2.toInt(),
    )

    private fun decide(
        key: GrayMask,
        cur: GrayMask,
        ego: MotionSample?,
        trackId: Int = SamResidu.NO_TRACK,
        samConfidence: Float = 1f,
        anchorFreshness: Float? = null,
    ): ResiduDecision = SamResidu.decide(key, cur, ego, MotionScenes.WIDTH, trackId, samConfidence, anchorFreshness)

    @Test
    fun `objet mobile sur fond fixe`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(16f, 0f))
        val decision = decide(key, cur, MotionSample(0f, 0f, 1f, CONFIDENT))
        assertEquals("$decision", ResiduVerdict.OBJECT_MOVED, decision.verdict)
        assertEquals(0.6f, decision.iou, 1e-6f)
    }

    @Test
    fun `pan simule et objet fixe, la camera bouge`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(6f, 2f))
        val decision = decide(key, cur, MotionSample(6f, 2f, 1f, CONFIDENT))
        assertEquals("$decision", ResiduVerdict.CAMERA_MOVED, decision.verdict)
        assertEquals(1f, decision.iou, 1e-6f)
    }

    @Test
    fun `erosion 1 px, pas de faux positif`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val decision = decide(key, mask(Box(97f, 33f, 159f, 71f)), MotionSample(0f, 0f, 1f, CONFIDENT))
        assertEquals("$decision", ResiduVerdict.CAMERA_MOVED, decision.verdict)
        assertTrue("$decision", decision.iou > 0.9f)
    }

    @Test
    fun `dilatation 1 px, pas de faux positif`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val decision = decide(key, mask(Box(95f, 31f, 161f, 73f)), MotionSample(0f, 0f, 1f, CONFIDENT))
        assertEquals("$decision", ResiduVerdict.CAMERA_MOVED, decision.verdict)
        assertTrue("$decision", decision.iou > 0.9f)
    }

    @Test
    fun `masque vide, pas d avis`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val empty = GrayMask(MotionScenes.WIDTH, MotionScenes.HEIGHT, ByteArray(MotionScenes.WIDTH * MotionScenes.HEIGHT))
        val ego = MotionSample(0f, 0f, 1f, CONFIDENT)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(empty, key, ego).verdict)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, empty, ego).verdict)
    }

    @Test
    fun `ego faible ou absent, pas d avis`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(16f, 0f))
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, cur, null).verdict)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, cur, MotionSample(0f, 0f, 1f, 0.05f)).verdict)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, cur, MotionSample(Float.NaN, 0f, 1f, CONFIDENT)).verdict)
    }

    @Test
    fun `score sam bas, pas d avis`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(16f, 0f))
        val ego = MotionSample(0f, 0f, 1f, CONFIDENT)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, cur, ego, samConfidence = 0.2f).verdict)
        assertEquals(ResiduVerdict.OBJECT_MOVED, decide(key, cur, ego, samConfidence = 0.9f).verdict)
    }

    @Test
    fun `ancre perimee, pas d avis`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(16f, 0f))
        val ego = MotionSample(0f, 0f, 1f, CONFIDENT)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, cur, ego, anchorFreshness = 0.1f).verdict)
        assertEquals(ResiduVerdict.OBJECT_MOVED, decide(key, cur, ego, anchorFreshness = 0.9f).verdict)
    }

    @Test
    fun `grilles differentes, pas d avis`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val small = MaskFixtures.rect(64, 64, 8, 8, 40, 40)
        val ego = MotionSample(0f, 0f, 1f, CONFIDENT)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, small, ego).verdict)
    }

    @Test
    fun `porte l id stable`() {
        val key = mask(MotionScenes.VEHICLE_BOX)
        val cur = mask(MotionScenes.VEHICLE_BOX.translated(16f, 0f))
        val decision = decide(key, cur, MotionSample(0f, 0f, 1f, CONFIDENT), trackId = 7)
        assertEquals(7, decision.trackId)
        assertEquals("$decision", ResiduVerdict.OBJECT_MOVED, decision.verdict)
    }

    @Test
    fun `convertit l ego-motion luma en pixels de masque`() {
        val motion = MotionSample(6f, 2f, 1f, 0.9f).toMaskMotion(lumaWidth = 256, maskWidth = 512)
        assertEquals(12.0, motion.dx, 1e-9)
        assertEquals(4.0, motion.dy, 1e-9)
        assertEquals(1.0, motion.scale, 1e-9)
        assertTrue(motion.isUsable)
    }

    @Test
    fun `conversion impossible, mouvement inutilisable`() {
        val motion = MotionSample(6f, 2f, 1f, 0.9f).toMaskMotion(lumaWidth = 0, maskWidth = 512)
        assertFalse(motion.isUsable)
    }

    @Test
    fun `estimateur muet sur scene statique, pas d avis`() {
        val scene = MotionScenes.scene()
        val ego = BlockMotionSource().estimateSince(scene, scene)
        assertEquals(MotionSample.Zero, ego)
        val key = mask(MotionScenes.VEHICLE_BOX)
        assertEquals(ResiduVerdict.UNCERTAIN, decide(key, key, ego).verdict)
    }

    @Test
    fun `la chaine estimateur-convertisseur fournit un ego utilisable`() {
        val scene = MotionScenes.scene()
        val sample = BlockMotionSource().estimateSince(scene, MotionScenes.shifted(scene, 4, -2))!!
        val motion = sample.toMaskMotion(MotionScenes.WIDTH, MotionScenes.WIDTH)
        assertTrue(motion.isUsable)
        assertEquals(4.0, motion.dx, 2.0)
        assertEquals(-2.0, motion.dy, 2.0)
    }

    private companion object {
        /** Confiance d'un ego sûr : franchement au-dessus de `Shift.TRUST_MIN` (0,12). */
        const val CONFIDENT = 0.9f
    }
}
