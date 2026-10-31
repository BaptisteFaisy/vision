package dev.sam3vision.core.local

import dev.sam3vision.core.mask.GrayMask
import dev.sam3vision.core.mask.MaskMotion
import dev.sam3vision.core.motion.BlockMotionSource
import dev.sam3vision.core.motion.MotionSource as GlobalMotionSource

/**
 * Pont estimateur → calque (PISTE 1) : la couture produite [GlobalMotionSource]
 * (`GrayMask` → `MotionSample`, plan 256×103) vue comme la couture consommée
 * [MotionSource] (`ByteArray` → [MaskMotion], même plan [LumaPlane]) — la
 * fusion L13 §3, sans réécrire de géométrie (translation + échelle autour du
 * centre des deux côtés, mêmes unités, seule l'enveloppe change).
 *
 * Plans incomplets ou tailles incohérentes : `null` (le calque reste inerte,
 * jamais de warp inventé). Une instance par thread/session, comme
 * [BlockMotionSource] (non sûr entre threads).
 */
class BlockMotionAdapter(
    private val source: GlobalMotionSource = BlockMotionSource(),
) : MotionSource {

    override fun estimateSince(prevLuma: ByteArray?, curLuma: ByteArray?): MaskMotion? {
        if (prevLuma == null || curLuma == null) return null
        if (prevLuma.size != LumaPlane.SIZE || curLuma.size != LumaPlane.SIZE) return null
        val sample = source.estimateSince(grayOf(prevLuma), grayOf(curLuma)) ?: return null
        return MaskMotion(
            dx = sample.dx.toDouble(),
            dy = sample.dy.toDouble(),
            scale = sample.scale.toDouble(),
            confidence = sample.confidence.toDouble(),
        )
    }

    /** Vue 8 bits du plan, sans copie (l'estimateur ne fait que lire). */
    private fun grayOf(plane: ByteArray): GrayMask = GrayMask(LumaPlane.WIDTH, LumaPlane.HEIGHT, plane)
}
