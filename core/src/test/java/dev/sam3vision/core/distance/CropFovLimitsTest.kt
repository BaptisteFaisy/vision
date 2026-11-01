package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FOV effectif après crop : les cas que le nominal D2 ne pinne pas — focale ou
 * taille inexploitables, crop trop fin (champ sous le plancher 15°), centre
 * optique hors crop, qualité MEDIUM, plein cadre.
 *
 * Dépend de D2 (`CropGeometry`) : ne compile pas sans.
 */
class CropFovLimitsTest {

    private val stream = LensSpec(68f, 100.4f, sensorLandscape = false)

    @Test
    fun `fov pour une focale ou une taille inexploitables vaut zero`() {
        assertEquals(0f, CropGeometry.fovDegForPixels(Float.NaN, 720), 0f)
        assertEquals(0f, CropGeometry.fovDegForPixels(-3f, 720), 0f)
        assertEquals(0f, CropGeometry.fovDegForPixels(533.7f, 0), 0f)
        assertEquals(0f, CropGeometry.fovDegForPixels(533.7f, -1), 0f)
    }

    @Test
    fun `crop trop fin, le champ sort des bornes et rien n'est invente`() {
        // V' = 2·atan(5 / 533,7) ≈ 1,1° : sous le plancher 15°.
        assertNull(CropGeometry.afterCrop(stream, 720, 1280, CropGeometry.Window(0, 635, 720, 10)))
    }

    @Test
    fun `centre optique hors crop, rien n'est invente`() {
        val decentered = stream.copy(principalXFrac = 0.05f)
        // Champs valides (H' ≈ 33,4°, V' ≈ 100,3°) mais cx à 36 px, hors du crop x=400.
        assertNull(CropGeometry.afterCrop(decentered, 720, 1280, CropGeometry.Window(400, 0, 320, 1280)))
    }

    @Test
    fun `medium bout en bout, meme contrat que high`() {
        val spec = LensSpec.fromHorizontalFov(66f, 0.75f).copy(sensorLandscape = false)
        val cropped = CropGeometry.afterCrop(spec, 504, 896, CropGeometry.Window(0, 306, 504, 283))!!
        assertEquals(66.0f, cropped.horizontalFovDeg, 0.05f)
        // V' = 2·atan(141,5 / 388,0) ≈ 40,0°
        assertEquals(40.0f, cropped.verticalFovDeg, 0.2f)
        assertTrue(cropped.sensorLandscape)
        assertTrue(cropped.usable)
    }

    @Test
    fun `plein cadre, les champs sont recalcules a focale constante`() {
        val same = CropGeometry.afterCrop(stream, 720, 1280, CropGeometry.Window(0, 0, 720, 1280))!!
        assertEquals(68f, same.horizontalFovDeg, 0.05f)
        // V' dérive des pixels carrés (100,3°), il ne recopie pas l'entrée V.
        assertEquals(100.3f, same.verticalFovDeg, 0.2f)
        assertTrue(same.usable)
    }
}
