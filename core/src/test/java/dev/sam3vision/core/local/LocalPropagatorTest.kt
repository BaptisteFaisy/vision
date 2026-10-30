package dev.sam3vision.core.local

import dev.sam3vision.core.mask.MaskFixtures
import dev.sam3vision.core.mask.MaskMotion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Chaîne de propagation ([LocalPropagator]) : le moteur décide, le warp exécute. Les
 * refus (pas de keyframe, pas d'estimateur, keyframe périmé, cadence) rendent `null`
 * avec le motif du moteur ; un mouvement translate le masque à l'échelle de sa grille.
 */
class LocalPropagatorTest {

    private val luma = ByteArray(LumaPlane.SIZE) { 64 }

    /** Estimateur fixe : +2 px en plan luma (256×103), confiance haute. */
    private val stub = MotionSource { _, _ -> MaskMotion(dx = 2.0, dy = 0.0, scale = 1.0, confidence = 0.95) }

    @Test
    fun `sans keyframe rien n est propage`() {
        val chain = LocalPropagator()
        chain.setMotion(stub)

        assertNull(chain.propagate(KEY_AT + 33L, luma))
        assertEquals(LocalReason.NO_KEYFRAME, chain.lastTick.reason)
    }

    @Test
    fun `sans estimateur le warp n est pas appele`() {
        val chain = LocalPropagator()
        chain.onKeyframe(MaskFixtures.rect(WIDTH, HEIGHT, 100, 50, 200, 150), luma, 0.9f, KEY_AT)

        assertNull(chain.propagate(KEY_AT + 33L, luma))
        assertEquals(LocalReason.NO_ESTIMATOR, chain.lastTick.reason)
    }

    @Test
    fun `un mouvement translate le masque a l echelle de sa grille`() {
        val chain = keyed()

        val out = chain.propagate(KEY_AT + 33L, luma)

        assertNotNull(out)
        // +2 px en plan 256 : +4 px en grille 512 (au plus proche, décalage entier exact).
        assertArrayEquals(intArrayOf(104, 50, 203, 149), MaskFixtures.box(out!!))
    }

    @Test
    fun `le tampon de sortie est reutilise d une frame a l autre`() {
        val chain = keyed()

        val first = chain.propagate(KEY_AT + 33L, luma)
        val second = chain.propagate(KEY_AT + 66L, luma)

        assertNotNull(first)
        assertSame("aucune allocation de grille par frame", first, second)
    }

    @Test
    fun `un mouvement nul recopie le keyframe`() {
        val chain = LocalPropagator()
        chain.setMotion(MotionSource { _, _ -> MaskMotion(confidence = 0.95) })
        val src = MaskFixtures.rect(WIDTH, HEIGHT, 100, 50, 200, 150)
        chain.onKeyframe(src, luma, 0.9f, KEY_AT)

        val out = chain.propagate(KEY_AT + 33L, luma)

        assertNotNull(out)
        assertArrayEquals(src.pixels, out!!.pixels)
    }

    @Test
    fun `un keyframe perime ne propage plus`() {
        val chain = keyed()

        assertNull(chain.propagate(KEY_AT + 2_000L, luma))
        assertEquals(LocalReason.STALE_KEYFRAME, chain.lastTick.reason)
    }

    @Test
    fun `une frame trop proche est refusee par la cadence`() {
        val chain = keyed()

        assertNotNull(chain.propagate(KEY_AT + 33L, luma))
        assertNull(chain.propagate(KEY_AT + 43L, luma))
        assertEquals(LocalReason.TOO_EARLY, chain.lastTick.reason)
    }

    @Test
    fun `reset oublie le keyframe et le tampon`() {
        val chain = keyed()
        assertNotNull(chain.propagate(KEY_AT + 33L, luma))
        chain.reset()

        assertNull(chain.propagate(KEY_AT + 66L, luma))
        assertEquals(LocalReason.NO_KEYFRAME, chain.lastTick.reason)
    }

    /** Chaîne branchée sur l'estimateur fixe, avec un keyframe rectangulaire à [KEY_AT]. */
    private fun keyed(): LocalPropagator {
        val chain = LocalPropagator()
        chain.setMotion(stub)
        chain.onKeyframe(MaskFixtures.rect(WIDTH, HEIGHT, 100, 50, 200, 150), luma, 0.9f, KEY_AT)
        return chain
    }

    private companion object {
        /** Grille du direct : 512×206. */
        const val WIDTH = 512
        const val HEIGHT = 206

        /** Instant du keyframe des tests : non nul (0 = aucun keyframe). */
        const val KEY_AT = 1_000L
    }
}
