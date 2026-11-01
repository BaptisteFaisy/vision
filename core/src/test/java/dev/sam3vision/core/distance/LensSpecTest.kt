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

    @Test
    fun `repli lunettes, capteur 68 degres ramene a la fenetre 9 16`() {
        val glasses = LensSpec.Glasses
        assertEquals(53.67f, glasses.horizontalFovDeg, 0.05f)
        assertEquals(83.93f, glasses.verticalFovDeg, 0.05f)
        assertFalse(glasses.sensorLandscape)
        assertTrue(glasses.usable)
        // Image portrait : la largeur porte l'horizontal du flux
        assertEquals(53.67f, glasses.widthFovDeg(225, 320), 0.05f)
        // 720 px de large : focale = 360 / tan(26,83°).
        assertEquals(711.6f, glasses.focalPx(720, 1280), 0.5f)
    }

    @Test
    fun `flux lunettes, table capteur vers fenetre 9 16`() {
        val hstn = LensSpec.glassesStreamForSensorH(70f)
        assertEquals(55.41f, hstn.horizontalFovDeg, 0.05f)
        assertEquals(86.07f, hstn.verticalFovDeg, 0.05f)
        assertTrue(hstn.usable)
        val vanguard = LensSpec.glassesStreamForSensorH(95f)
        assertEquals(78.60f, vanguard.horizontalFovDeg, 0.05f)
        assertEquals(111.00f, vanguard.verticalFovDeg, 0.1f)
        assertTrue(vanguard.usable)
    }

    @Test
    fun `calibrage lunettes des Reglages, champ force sinon repli`() {
        val forced = LensSpec.fromGlassesSettings(70f)
        assertEquals(70f, forced.horizontalFovDeg, 0.01f)
        assertEquals(102.45f, forced.verticalFovDeg, 0.2f)
        assertFalse(forced.sensorLandscape)
        assertTrue(forced.usable)
        assertEquals(LensSpec.Glasses, LensSpec.fromGlassesSettings(0f))
        assertEquals(LensSpec.Glasses, LensSpec.fromGlassesSettings(150f))
    }

    @Test
    fun `centre optique par defaut au milieu des pixels`() {
        val (cx, cy) = LensSpec.Default.principalPx(512, 384)
        assertEquals(255.5f, cx, 0.001f)
        assertEquals(191.5f, cy, 0.001f)
    }

    @Test
    fun `centre optique suit les fractions et l'echelle`() {
        val lens = LensSpec(67f, 52.85f, principalXFrac = 0.6f, principalYFrac = 0.4f)
        val (cx, cy) = lens.principalPx(512, 384)
        assertEquals(0.6f * 511f, cx, 0.001f)
        assertEquals(0.4f * 383f, cy, 0.001f)
    }

    @Test
    fun `centre optique hors capteur rend le calibrage inutilisable`() {
        assertFalse(LensSpec(67f, 53f, principalXFrac = 1.5f).usable)
        assertFalse(LensSpec(67f, 53f, principalYFrac = Float.NaN).usable)
        assertTrue(LensSpec(67f, 53f, principalXFrac = 0f, principalYFrac = 1f).usable)
    }

    @Test
    fun `focale en pixels et centre optique retrouvent leurs entrees`() {
        val lens = LensSpec.fromFocalPx(2986.1f, 2986.1f, 1999.5f, 1499.5f, 4000, 3000)!!
        assertEquals(67.63f, lens.horizontalFovDeg, 0.1f)
        assertEquals(53.34f, lens.verticalFovDeg, 0.1f)
        assertEquals(2986.1f, lens.focalPx(4000, 3000), 1f)
        val (cx, cy) = lens.principalPx(4000, 3000)
        assertEquals(1999.5f, cx, 0.01f)
        assertEquals(1499.5f, cy, 0.01f)
    }

    @Test
    fun `focale en pixels incoherente est refusee`() {
        assertEquals(null, LensSpec.fromFocalPx(0f, 2986f, 1999f, 1499f, 4000, 3000))
        assertEquals(null, LensSpec.fromFocalPx(2986f, -1f, 1999f, 1499f, 4000, 3000))
        assertEquals(null, LensSpec.fromFocalPx(2986f, 2986f, 5000f, 1499f, 4000, 3000))
        assertEquals(null, LensSpec.fromFocalPx(2986f, 2986f, 1999f, 1499f, 0, 3000))
    }

    @Test
    fun `mesure mire delegue au capteur pleine zone`() {
        val measured = LensSpec.fromMeasured(4.3f, 5.76f, 4.32f, 4000, 3000)!!
        assertEquals(LensSpec.fromSensor(4.3f, 5.76f, 4.32f), measured)
        assertEquals(67.7f, measured.horizontalFovDeg, 0.5f)
        assertEquals(53.4f, measured.verticalFovDeg, 0.5f)
        assertTrue(measured.usable)
        assertEquals(null, measured.distortion)
        assertEquals(null, LensSpec.fromMeasured(0f, 5.76f, 4.32f, 4000, 3000))
        assertEquals(null, LensSpec.fromMeasured(4.3f, 5.76f, 4.32f, 0, 3000))
        assertEquals(null, LensSpec.fromMeasured(4.3f, 5.76f, 4.32f, 4000, -1))
    }

    @Test
    fun `distorsion non finie rend le calibrage inutilisable`() {
        assertFalse(LensSpec(67f, 53f, distortion = RadialDistortion(Float.NaN)).usable)
        assertFalse(LensSpec(67f, 53f, distortion = RadialDistortion(0f, Float.POSITIVE_INFINITY)).usable)
        assertTrue(LensSpec(67f, 53f, distortion = RadialDistortion(-0.1f, 0.01f)).usable)
        assertTrue(LensSpec(67f, 53f, distortion = RadialDistortion(0f, 0f)).usable)
    }

    @Test
    fun `distorsion null signifie sans mesure, pas sans distorsion`() {
        assertFalse(LensSpec.Default.hasMeasuredDistortion)
        assertTrue(LensSpec.Default.usable)
        val measuredFlat = LensSpec(67f, 53f, distortion = RadialDistortion(0f, 0f))
        assertTrue(measuredFlat.hasMeasuredDistortion)
        assertTrue(measuredFlat.usable)
        val measured = LensSpec(67f, 53f, distortion = RadialDistortion(-0.1f, 0.01f))
        assertTrue(measured.hasMeasuredDistortion)
        assertTrue(measured.usable)
    }
}
