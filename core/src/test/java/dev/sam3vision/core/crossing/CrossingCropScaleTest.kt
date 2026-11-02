package dev.sam3vision.core.crossing

import org.junit.Assert.assertEquals
import org.junit.Test

class CrossingCropScaleTest {

    @Test
    fun `boite large reduite sur le plus grand cote`() {
        assertEquals(96 to 48, CrossingCropScale.scaledSize(200, 100))
    }

    @Test
    fun `boite haute reduite sur le plus grand cote`() {
        assertEquals(48 to 96, CrossingCropScale.scaledSize(100, 200))
    }

    @Test
    fun `carre deja a la borne inchange`() {
        assertEquals(96 to 96, CrossingCropScale.scaledSize(96, 96))
    }

    @Test
    fun `petite boite inchangee sans reechantillonnage`() {
        assertEquals(50 to 20, CrossingCropScale.scaledSize(50, 20))
    }

    @Test
    fun `lampe elancee 12x96 garde sa largeur`() {
        assertEquals(12 to 96, CrossingCropScale.scaledSize(12, 96))
    }

    @Test
    fun `cote tres fin jamais nul`() {
        assertEquals(1 to 96, CrossingCropScale.scaledSize(1, 400))
        assertEquals(96 to 1, CrossingCropScale.scaledSize(400, 1))
    }

    @Test
    fun `rapport conserve sur les cas reels`() {
        assertEquals(96 to 32, CrossingCropScale.scaledSize(300, 100))
        assertEquals(72 to 96, CrossingCropScale.scaledSize(150, 200))
    }

    @Test
    fun `boite vide ou negative sans dimension`() {
        assertEquals(0 to 0, CrossingCropScale.scaledSize(0, 10))
        assertEquals(0 to 0, CrossingCropScale.scaledSize(10, -1))
    }
}
