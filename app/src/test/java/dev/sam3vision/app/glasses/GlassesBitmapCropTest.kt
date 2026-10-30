package dev.sam3vision.app.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recadrage source du chemin bitmap du direct ([bitmapSourceCrop]) : le
 * rectangle centré du bitmap qui a exactement le ratio du canvas.
 *
 * Le canvas est à taille vidéo et la matrice « remplit la vue » l'agrandit
 * ensuite : dessiner le bitmap étiré plein canvas (l'ancien comportement)
 * déformait l'image puis la re-zoomait — double mise à l'échelle. Ici le
 * ratio est conservé à la source, et le cas nominal (bitmap à taille vidéo)
 * passe en 1:1, la matrice faisant l'unique upscale.
 */
class GlassesBitmapCropTest {

    @Test
    fun `meme ratio, le bitmap passe entier`() {
        // Cas nominal : bitmap et canvas à taille vidéo (HIGH 720×1280) → 1:1.
        assertEquals(CropRect(0, 0, 720, 1280), bitmapSourceCrop(720, 1280, 720, 1280))
        // Même ratio, tailles différentes : rien à couper non plus.
        assertEquals(CropRect(0, 0, 504, 896), bitmapSourceCrop(504, 896, 1080, 1920))
    }

    @Test
    fun `bitmap paysage dans canvas portrait, les cotes tombent centres`() {
        // 1920×1080 dans 720×1280 : 607 px gardés sur 1920, centrés.
        assertEquals(CropRect(656, 0, 607, 1080), bitmapSourceCrop(1920, 1080, 720, 1280))
    }

    @Test
    fun `bitmap portrait dans canvas paysage, le haut et le bas tombent centres`() {
        // 504×896 dans 1080×600 : 280 px gardés sur 896, centrés.
        assertEquals(CropRect(0, 308, 504, 280), bitmapSourceCrop(504, 896, 1080, 600))
    }

    @Test
    fun `un ratio extreme garde au moins un pixel`() {
        // 1×1000 dans 1000×1 : la hauteur coupée tomberait à 0, on garde 1.
        assertEquals(CropRect(0, 499, 1, 1), bitmapSourceCrop(1, 1000, 1000, 1))
    }

    @Test
    fun `dimensions degenerees, le bitmap passe entier sans crasher`() {
        assertEquals(CropRect(0, 0, 0, 480), bitmapSourceCrop(0, 480, 1080, 600))
        assertEquals(CropRect(0, 0, 640, 480), bitmapSourceCrop(640, 480, 0, 0))
    }
}
