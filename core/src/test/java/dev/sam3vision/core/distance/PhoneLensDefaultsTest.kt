package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-régression des défauts téléphone : la calibration lunettes (D1-D3) ajoute
 * des chemins, elle ne déplace aucun de ces chiffres. Tout écart = régression
 * du Direct et de la page caméra sur le capteur du téléphone.
 *
 * 100 % API existantes : passe sur le code actuel, avant comme après D1-D3.
 */
class PhoneLensDefaultsTest {

    @Test
    fun `le repli reste un grand-angle 67 degres utilisable`() {
        assertEquals(67f, LensSpec.DEFAULT_H_FOV_DEG, 0f)
        assertEquals(67f, LensSpec.Default.horizontalFovDeg, 0.01f)
        assertTrue(LensSpec.Default.usable)
    }

    @Test
    fun `le repli suppose un capteur telephone 4 3`() {
        assertEquals(52.75f, LensSpec.Default.verticalFovDeg, 0.2f)
        assertTrue(LensSpec.Default.sensorLandscape)
    }

    @Test
    fun `focale du direct a 512 px inchangee`() {
        assertEquals(387.1f, LensSpec.Default.focalPx(512, 384), 0.5f)
    }

    @Test
    fun `focale iSee a 320 px inchangee`() {
        assertEquals(181.5f, LensSpec.Default.focalPx(180, 320), 0.5f)
    }

    @Test
    fun `bornes de champ 15 a 130 degres`() {
        assertEquals(15f, LensSpec.FOV_MIN, 0f)
        assertEquals(130f, LensSpec.FOV_MAX, 0f)
        assertTrue(LensSpec.fromHorizontalFov(67f).usable)
    }

    @Test
    fun `demi-tangente partagee du guidage inchangee`() {
        assertEquals(0.6613f, LensSpec.halfTangent(67f), 0.001f)
    }
}
