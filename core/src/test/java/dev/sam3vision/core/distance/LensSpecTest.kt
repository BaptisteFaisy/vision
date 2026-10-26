package dev.sam3vision.core.distance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Calibrage optique : focale en pixels depuis les champs de vision du capteur. */
class LensSpecTest {

    @Test
    fun `focale horizontale suit la formule du stenope`() {
        val lens = LensSpec(67f, 52.85f)
        // 256 / tan(33,5°) = 386,8 px
        assertEquals(386.8f, lens.focalPx(512, 384), 0.5f)
    }

    @Test
    fun `image portrait utilise l'axe vertical du capteur`() {
        val landscape = LensSpec(67f, 52.85f, sensorLandscape = true)
        val focalLandscape = landscape.focalPx(512, 384)
        val focalPortrait = landscape.focalPx(384, 512)
        // même optique : la focale en pixels ne change pas avec la rotation
        assertTrue(abs(focalLandscape - focalPortrait) < 2f)
    }

    @Test
    fun `capteur natif portrait inverse la lecture`() {
        val portraitSensor = LensSpec(67f, 52.85f, sensorLandscape = false)
        assertEquals(52.85f, portraitSensor.widthFovDeg(512, 384), 0.01f)
        assertEquals(67f, portraitSensor.widthFovDeg(384, 512), 0.01f)
    }

    @Test
    fun `calibrage hors bornes est inutilisable`() {
        assertFalse(LensSpec(0f, 0f).usable)
        assertFalse(LensSpec(160f, 120f).usable)
        assertTrue(LensSpec(67f, 53f).usable)
        assertEquals(0f, LensSpec(0f, 0f).focalPx(512, 384), 0f)
        assertEquals(0f, LensSpec.Default.focalPx(0, 0), 0f)
    }

    @Test
    fun `champ vertical deduit d'un capteur 4 3`() {
        val lens = LensSpec.fromHorizontalFov(67f)
        assertEquals(67f, lens.horizontalFovDeg, 0.01f)
        assertEquals(52.85f, lens.verticalFovDeg, 0.2f)
        assertTrue(lens.usable)
    }

    @Test
    fun `capteur physique donne les deux angles`() {
        // 26 mm équivalent 4,3 mm de focale et 5,76 mm de large : ~67° horizontaux
        val lens = LensSpec.fromSensor(4.3f, 5.76f, 4.32f)!!
        assertEquals(67.7f, lens.horizontalFovDeg, 0.5f)
        assertEquals(53.4f, lens.verticalFovDeg, 0.5f)
        assertTrue(lens.sensorLandscape)
        assertEquals(null, LensSpec.fromSensor(0f, 5.76f, 4.32f))
    }

    @Test
    fun `calibrage des Reglages, champ force sinon repli`() {
        assertEquals(90f, LensSpec.fromSettings(90f).horizontalFovDeg, 0.01f)
        assertEquals(LensSpec.Default, LensSpec.fromSettings(0f))
        assertEquals(LensSpec.Default, LensSpec.fromSettings(150f))
    }
}
