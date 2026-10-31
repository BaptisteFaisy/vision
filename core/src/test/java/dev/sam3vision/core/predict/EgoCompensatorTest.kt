package dev.sam3vision.core.predict

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.motion.BlockMotionSource
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.motion.MotionScenes
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vote fond vs objet (PISTE 1) : le résidu boîte − ego-motion dit qui bouge,
 * le zoom du fond décote les croissances. Les séquences caméra sont
 * synthétiques (scènes [MotionScenes], avant-plan texturé peint ici — les
 * deux estimateurs réels tournent, aucun chiffre simulé).
 */
class EgoCompensatorTest {

    private val motion = BlockMotionSource()

    @Test
    fun `pan + voiture garee - la boite suit le fond, statique`() {
        val scene = egoScene(bgDx = 8, bgDy = 2, boxDx = 8, boxDy = 2)
        val (bgPrev, bgCur) = pureBg(bgDx = 8, bgDy = 2)
        val global = motion.estimateSince(bgPrev, bgCur)
        assertNotNull("le pan se mesure : $global", global)
        assertEquals(8f, global!!.dx, 2f)
        assertEquals(2f, global.dy, 2f)
        val shift = estimateShift(scene.prev, scene.cur, scene.prevBox, searchPx = 10)
        assertNotNull(shift)
        assertEquals(8, shift!!.dx)
        assertEquals(2, shift.dy)
        assertTrue("boîte texturée : ${shift.confidence}", shift.trustworthy)

        // 2 s : même à ±2 px sur les deux axes, le résidu reste sous l'arrêt.
        val decision = EgoCompensator.decide(shift, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 2000L)

        assertEquals(EgoVerdict.STATIC, decision.verdict)
    }

    @Test
    fun `fixe + approche - boite franche sur fond immobile, mobile`() {
        val scene = egoScene(bgDx = 0, bgDy = 0, boxDx = 8, boxDy = 0)
        val still = MotionScenes.scene()
        val global = motion.estimateSince(still, MotionScenes.scene())
        val shift = estimateShift(scene.prev, scene.cur, scene.prevBox, searchPx = 10)!!
        assertEquals(8, shift.dx)
        assertEquals(0, shift.dy)
        assertTrue("boîte texturée : ${shift.confidence}", shift.trustworthy)

        assertEquals(EgoVerdict.MOVING, EgoCompensator.decide(shift, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 200L).verdict)
        assertEquals("sans fond, la boîte seule décide", EgoVerdict.MOVING,
            EgoCompensator.decide(shift, WIDTH, HEIGHT, null, WIDTH, HEIGHT, 200L).verdict)
    }

    @Test
    fun `pan suit voiture - boite fixe sur fond qui defile, pas d arret`() {
        val scene = egoScene(bgDx = 12, bgDy = 4, boxDx = 0, boxDy = 0)
        val background = MotionScenes.scene()
        val global = motion.estimateSince(background, MotionScenes.shifted(background, 12, 4))
        assertNotNull("le pan se mesure : $global", global)
        val shift = estimateShift(scene.prev, scene.cur, scene.prevBox, searchPx = 10)!!
        assertEquals(0, shift.dx)
        assertEquals(0, shift.dy)

        // 1 s : même à −2 px sur les deux axes, le résidu reste franc.
        val decision = EgoCompensator.decide(shift, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 1000L)

        assertEquals(EgoVerdict.MOVING, decision.verdict)
    }

    @Test
    fun `dilatation SAM une frame - pas de faux mobile`() {
        val box = MotionScenes.VEHICLE_BOX
        val prev = paintBox(MotionScenes.scene(), box)
        val cur = paintBox(MotionScenes.scene(), box.scaled(1.2f, 1.2f))
        val shift = estimateShift(prev, cur, box)!!
        assertEquals(0, shift.dx)
        assertEquals(0, shift.dy)

        assertEquals(EgoVerdict.STATIC, EgoCompensator.decide(shift, WIDTH, HEIGHT, null, WIDTH, HEIGHT, 100L).verdict)
    }

    @Test
    fun `zoom fond mesure - croissance lente decotee, croissance franche gardee`() {
        val background = MotionScenes.scene()
        val sample = motion.estimateSince(background, MotionScenes.zoomed(background, 1.06f))
        assertNotNull("le zoom se mesure : $sample", sample)

        assertTrue(EgoCompensator.discountGrowth(0.15f, sample, 200L).discounted)
        val kept = EgoCompensator.discountGrowth(2.5f, sample, 200L)
        assertFalse("un vrai rapprochement survit au zoom", kept.discounted)
        assertTrue(kept.residual > 1f)
    }

    @Test
    fun `boite douteuse non nulle - incertain, jamais invente`() {
        val global = MotionSample(0f, 0f, 1f, 0.9f)
        assertEquals(EgoVerdict.UNCERTAIN,
            EgoCompensator.decide(5f, 0f, 0.05f, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 100L).verdict)
        val noShift: Shift? = null
        assertEquals(EgoVerdict.UNCERTAIN,
            EgoCompensator.decide(noShift, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 100L).verdict)
    }

    @Test
    fun `bande de doute - entre arret et franc, incertain`() {
        val global = MotionSample(0f, 0f, 1f, 0.9f)
        val edge = 0.025f * WIDTH * 0.1f
        assertEquals(EgoVerdict.UNCERTAIN,
            EgoCompensator.decide(edge, 0f, 0.9f, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 100L).verdict)
    }

    @Test
    fun `projection zoom - centre immobile, bord emporte`() {
        val zoom = MotionSample(0f, 0f, 1.1f, 0.9f)
        val center = EgoCompensator.projectedEgo(zoom, 200, 100, 1000L, 0.5f, 0.5f)
        assertEquals(0f, center.first, 1e-6f)
        assertEquals(0f, center.second, 1e-6f)
        val edge = EgoCompensator.projectedEgo(zoom, 200, 100, 1000L, 1f, 0.5f)
        assertEquals(0.05f, edge.first, 1e-6f)
        assertEquals(0f, edge.second, 1e-6f)
    }

    @Test
    fun `confiance combinee - la plus faible des deux voies`() {
        val global = MotionSample(0f, 0f, 1f, 0.5f)
        val decision = EgoCompensator.decide(0f, 0f, 0.9f, WIDTH, HEIGHT, global, WIDTH, HEIGHT, 100L)
        assertEquals(EgoVerdict.STATIC, decision.verdict)
        assertEquals(0.5f, decision.confidence, 1e-6f)
    }

    @Test
    fun `discountGrowth - sans fond prouve, rien n est decote`() {
        assertFalse(EgoCompensator.discountGrowth(0.1f, null, 100L).discounted)
        val weak = MotionSample(0f, 0f, 1.04f, 0.05f)
        assertFalse(EgoCompensator.discountGrowth(0.1f, weak, 100L).discounted)
        val idle = EgoCompensator.discountGrowth(0f, MotionSample(0f, 0f, 1.04f, 0.9f), 100L)
        assertEquals(0f, idle.residual, 1e-6f)
        assertFalse(idle.discounted)
    }

    @Test
    fun `areaGrowing - brut sans fond, decote sous zoom`() {
        assertTrue(EgoCompensator.areaGrowing(100f, 121f, 1000L, null))
        assertFalse(EgoCompensator.areaGrowing(100f, 90f, 1000L, null))
        val zoom = MotionSample(0f, 0f, 1.02f, 0.9f)
        assertFalse("croissance 10 %/s sous zoom 20 %/s", EgoCompensator.areaGrowing(10000f, 10201f, 100L, zoom))
        assertTrue("croissance 100 %/s sous zoom 20 %/s", EgoCompensator.areaGrowing(10000f, 12100f, 100L, zoom))
    }

    @Test
    fun `linearRateFromAreas - racine de l aire, null si injugeable`() {
        assertEquals(0.1f, EgoCompensator.linearRateFromAreas(10000f, 12100f, 1000L)!!, 1e-6f)
        assertEquals(null, EgoCompensator.linearRateFromAreas(10000f, 12100f, 0L))
        assertEquals(null, EgoCompensator.linearRateFromAreas(0f, 12100f, 1000L))
    }

    /** Paire d'images + boîte : fond décalé de (bg), avant-plan texturé déplacé de (box). */
    private data class EgoScene(val prev: GrayMask, val cur: GrayMask, val prevBox: Box)

    private fun egoScene(bgDx: Int, bgDy: Int, boxDx: Int, boxDy: Int): EgoScene {
        val box = MotionScenes.VEHICLE_BOX
        val prev = paintBox(MotionScenes.scene(), box)
        val background = MotionScenes.shifted(MotionScenes.scene(), bgDx, bgDy)
        val cur = paintBox(background, box.translated(boxDx.toFloat(), boxDy.toFloat()), boxDx, boxDy)
        return EgoScene(prev, cur, box)
    }

    /** Couple fond pur (sans avant-plan) du même pan : la mesure globale de référence. */
    private fun pureBg(bgDx: Int, bgDy: Int): Pair<GrayMask, GrayMask> {
        val background = MotionScenes.scene()
        return background to MotionScenes.shifted(background, bgDx, bgDy)
    }

    /** Copie de [mask] avec [at] remplie de texture (décalée de tex : elle suit la boîte). */
    private fun paintBox(mask: GrayMask, at: Box, texDx: Int = 0, texDy: Int = 0): GrayMask {
        val out = GrayMask(mask.width, mask.height, mask.pixels.copyOf())
        val x0 = at.x1.toInt().coerceIn(0, out.width)
        val y0 = at.y1.toInt().coerceIn(0, out.height)
        val x1 = at.x2.toInt().coerceIn(x0, out.width)
        val y1 = at.y2.toInt().coerceIn(y0, out.height)
        for (y in y0 until y1) {
            for (x in x0 until x1) out.pixels[y * out.width + x] = texture(x - texDx, y - texDy).toByte()
        }
        return out
    }

    /** Texture riche mais lisse, comme une vraie image (miroir de `BlockShiftTest`). */
    private fun texture(x: Int, y: Int): Int {
        val v = 60 + 45 * sin(x * 0.63) + 40 * sin(y * 0.87) + 28 * sin((x + y) * 0.29)
        return v.toInt().coerceIn(0, 255)
    }

    companion object {
        private const val WIDTH = 256
        private const val HEIGHT = 103
    }
}
