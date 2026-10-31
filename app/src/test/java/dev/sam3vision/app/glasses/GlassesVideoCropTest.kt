package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le recadrage « remplit la vue » du direct lunettes : un `TextureView` étire son buffer
 * plein cadre, puis applique `setTransform` en coordonnées vue — la matrice est donc la
 * correction centrée ([textureViewCrop]), pas le placage échelle + décalage (qui
 * sur-zoomait et déformait la rue : l'ancienne convention, testée ici avant, était fausse
 * pour un `TextureView`).
 */
class GlassesVideoCropTest {

    @Test
    fun `meme format, la matrice est l identite`() {
        assertEquals(TextureCrop(1f, 1f), textureViewCrop(srcW = 504, srcH = 896, dstW = 1008, dstH = 1792))
        assertEquals(TextureCrop(1f, 1f), textureViewCrop(srcW = 720, srcH = 1280, dstW = 720, dstH = 1280))
    }

    @Test
    fun `video portrait dans vue paysage, seul l axe vertical corrige`() {
        // 720×1280 (flux HIGH) dans une rue 1080×600 : l'étirement remplit déjà la
        // largeur (×1.5), la matrice ne grossit que la hauteur (×3.2).
        val crop = textureViewCrop(srcW = 720, srcH = 1280, dstW = 1080, dstH = 600)
        assertEquals(1f, crop.scaleX, 0f)
        assertEquals(3.2f, crop.scaleY, 1e-4f)
    }

    @Test
    fun `video paysage dans vue portrait, seul l axe horizontal corrige`() {
        val crop = textureViewCrop(srcW = 1792, srcH = 1008, dstW = 504, dstH = 896)
        assertEquals((896f / 1008f) / (504f / 1792f), crop.scaleX, 1e-4f)
        assertEquals(1f, crop.scaleY, 0f)
        assertTrue(crop.scaleX > 1f)
    }

    @Test
    fun `le total etirement plus matrice est uniforme, sans deformation`() {
        // Non-régression du bug : quel que soit le couple vidéo/vue, l'échelle totale
        // (étirement plein cadre × matrice) est la même sur les deux axes, donc aucun
        // étirement visible — et un axe de la matrice vaut toujours 1.
        val cases = listOf(
            Quad(720, 1280, 1080, 600),
            Quad(720, 1280, 1080, 1920),
            Quad(504, 896, 1792, 1008),
            Quad(1920, 1080, 500, 900),
            Quad(640, 480, 1080, 600),
        )
        for ((srcW, srcH, dstW, dstH) in cases) {
            val crop = textureViewCrop(srcW, srcH, dstW, dstH)
            val totalX = dstW / srcW.toFloat() * crop.scaleX
            val totalY = dstH / srcH.toFloat() * crop.scaleY
            val fill = maxOf(dstW / srcW.toFloat(), dstH / srcH.toFloat())
            assertEquals("total horizontal $srcW×$srcH → $dstW×$dstH", fill, totalX, 1e-4f)
            assertEquals("total vertical $srcW×$srcH → $dstW×$dstH", fill, totalY, 1e-4f)
            assertTrue("un axe vaut 1 ($crop)", crop.scaleX == 1f || crop.scaleY == 1f)
            assertTrue("l'autre remplit ($crop)", crop.scaleX >= 1f && crop.scaleY >= 1f)
        }
    }

    @Test
    fun `dimensions degenerees, pas de recadrage`() {
        assertEquals(TextureCrop(1f, 1f), textureViewCrop(0, 896, 1792, 1008))
        assertEquals(TextureCrop(1f, 1f), textureViewCrop(504, 896, 0, 0))
    }

    private data class Quad(val srcW: Int, val srcH: Int, val dstW: Int, val dstH: Int)
}
