package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le recadrage « remplit la vue » du direct lunettes : la vidéo couvre toute la vue, centrée,
 * sans déformation — elle déborde sur un axe, jamais l'inverse.
 */
class GlassesVideoCropTest {

    @Test
    fun `meme format, la video remplit exactement la vue`() {
        val crop = cropTransform(srcW = 504, srcH = 896, dstW = 1008, dstH = 1792)
        assertEquals(2f, crop.scale, 0f)
        assertEquals(0f, crop.dx, 0f)
        assertEquals(0f, crop.dy, 0f)
    }

    @Test
    fun `video portrait dans vue paysage, debordement vertical centre`() {
        // 504×896 (flux DAT) dans une rue 1792×1008 : l'échelle colle la largeur.
        val crop = cropTransform(srcW = 504, srcH = 896, dstW = 1792, dstH = 1008)
        assertEquals(1792f / 504f, crop.scale, 1e-4f)
        assertEquals(0f, crop.dx, 1e-2f)
        assertTrue(crop.dy < 0f)
        // Symétrie : autant de débordement en haut qu'en bas.
        val overflow = -(crop.dy * 2f) + 1008f - 896f * crop.scale
        assertEquals(0f, overflow, 1e-2f)
    }

    @Test
    fun `video paysage dans vue portrait, debordement horizontal centre`() {
        val crop = cropTransform(srcW = 1792, srcH = 1008, dstW = 504, dstH = 896)
        assertEquals(896f / 1008f, crop.scale, 1e-4f)
        assertEquals(0f, crop.dy, 1e-2f)
        assertTrue(crop.dx < 0f)
    }

    @Test
    fun `dimensions degenerees, pas de recadrage`() {
        assertEquals(CropTransform(1f, 0f, 0f), cropTransform(0, 896, 1792, 1008))
        assertEquals(CropTransform(1f, 0f, 0f), cropTransform(504, 896, 0, 0))
    }
}
