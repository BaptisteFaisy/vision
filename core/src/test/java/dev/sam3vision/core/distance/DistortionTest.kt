package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Redressement radial : identité au centre et sans coeffs, bords plus corrigés. */
class DistortionTest {

    private companion object {
        const val WIDTH = 512
        const val HEIGHT = 384
        const val CX = 255.5f
        const val CY = 191.5f
    }

    private val norm = Distortion.normPx(WIDTH, HEIGHT)

    @Test
    fun `demi-diagonale comme rayon de normalisation`() {
        assertEquals(320f, norm, 0.001f)
        assertEquals(0f, Distortion.normPx(0, HEIGHT), 0f)
    }

    @Test
    fun `sans distorsion les points sont inchanges`() {
        assertEquals(100f, Distortion.undistortX(100f, 300f, CX, CY, norm, null), 0f)
        assertEquals(300f, Distortion.undistortY(100f, 300f, CX, CY, norm, null), 0f)
        val zero = RadialDistortion(0f, 0f)
        assertEquals(100f, Distortion.undistortX(100f, 300f, CX, CY, norm, zero), 0f)
        assertEquals(300f, Distortion.undistortY(100f, 300f, CX, CY, norm, zero), 0f)
    }

    @Test
    fun `le centre optique est invariant`() {
        val barrel = RadialDistortion(-0.1f, 0.02f)
        assertEquals(CX, Distortion.undistortX(CX, CY, CX, CY, norm, barrel), 0f)
        assertEquals(CY, Distortion.undistortY(CX, CY, CX, CY, norm, barrel), 0f)
    }

    @Test
    fun `le barillet rapproche du centre, plus fort aux bords`() {
        val barrel = RadialDistortion(-0.1f)
        // sous le centre : l'ordonnée remonte, sans jamais passer le centre
        val near = Distortion.undistortY(CX, CY + 20f, CX, CY, norm, barrel)
        val far = Distortion.undistortY(CX, HEIGHT - 1f, CX, CY, norm, barrel)
        assertTrue(near < CY + 20f)
        assertTrue(near > CY)
        assertTrue(far < HEIGHT - 1f)
        assertTrue(far > CY)
        // le bord (~6,9 px) est corrigé bien plus que le quasi-centre (~0,01 px)
        assertTrue(abs(far - (HEIGHT - 1f)) > abs(near - (CY + 20f)) * 10f)
        // symétrique en x : le bord gauche revient vers le centre
        val left = Distortion.undistortX(0f, CY, CX, CY, norm, barrel)
        assertTrue(left > 0f)
        assertTrue(left < CX)
    }

    @Test
    fun `le second coefficient renforce la correction aux bords`() {
        val near = Distortion.undistortY(CX, HEIGHT - 1f, CX, CY, norm, RadialDistortion(-0.1f))
        val stronger = Distortion.undistortY(CX, HEIGHT - 1f, CX, CY, norm, RadialDistortion(-0.1f, -0.05f))
        assertTrue(abs(stronger - (HEIGHT - 1f)) > abs(near - (HEIGHT - 1f)))
    }
}
