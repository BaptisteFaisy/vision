package dev.sam3vision.core.local

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.motion.MotionSample
import dev.sam3vision.core.motion.MotionSource as GlobalMotionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pont estimateur → calque (PISTE 1) : [BlockMotionAdapter] ne fait que
 * changer l'enveloppe (`MotionSample` → `MaskMotion`, mêmes unités, plan
 * [LumaPlane]) et refuse les plans incohérents (`null`, jamais de warp inventé).
 */
class BlockMotionAdapterTest {

    /** Source pilotée : [sample] renvoyé tel quel, vues reçues enregistrées. */
    private class FixedSource(private val sample: MotionSample?) : GlobalMotionSource {
        var calls = 0
        var prevW = 0
        var prevH = 0
        override fun estimateSince(prevLuma: GrayMask, curLuma: GrayMask): MotionSample? {
            calls += 1
            prevW = prevLuma.width
            prevH = prevLuma.height
            return sample
        }
    }

    private fun plane(fill: Byte = 64): ByteArray = ByteArray(LumaPlane.SIZE) { fill }

    @Test
    fun `pont MotionSample vers MaskMotion - memes valeurs`() {
        val source = FixedSource(MotionSample(2f, -1f, 1.02f, 0.9f))
        val got = BlockMotionAdapter(source).estimateSince(plane(), plane(65))!!

        assertEquals(2.0, got.dx, 0.0)
        assertEquals(-1.0, got.dy, 0.0)
        assertEquals(1.02, got.scale, 1e-6)
        assertEquals(0.9, got.confidence, 1e-6)
        assertEquals(1, source.calls)
        assertEquals(LumaPlane.WIDTH, source.prevW)
        assertEquals(LumaPlane.HEIGHT, source.prevH)
    }

    @Test
    fun `plans absents ou tailles incoherentes - null sans appeler la source`() {
        val source = FixedSource(MotionSample(2f, 1f, 1f, 0.9f))
        val adapter = BlockMotionAdapter(source)
        val full = plane()
        val short = ByteArray(LumaPlane.SIZE - 1)
        val long = ByteArray(LumaPlane.SIZE + 1)

        assertNull(adapter.estimateSince(null, full))
        assertNull(adapter.estimateSince(full, null))
        assertNull(adapter.estimateSince(short, full))
        assertNull(adapter.estimateSince(full, short))
        assertNull(adapter.estimateSince(long, full))
        assertNull(adapter.estimateSince(full, long))
        assertNull(adapter.estimateSince(short, long))
        assertEquals("aucun appel sur plans incohérents", 0, source.calls)
    }

    @Test
    fun `source muette - null propage`() {
        val adapter = BlockMotionAdapter(FixedSource(null))

        assertNull(adapter.estimateSince(plane(), plane()))
    }
}
