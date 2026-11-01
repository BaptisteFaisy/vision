package dev.sam3vision.app.live

import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Priorité du calibrage des distances : champ forcé, capteur lu, repli grand-angle. */
class LiveLensTest {

    private val sensor = LensSpec(73.32f, 58.34f)

    @Test
    fun `le champ forcé prime sur le capteur`() {
        assertEquals(70f, chosenLens(70f, sensor).horizontalFovDeg, 0.001f)
    }

    @Test
    fun `le capteur prime sur le repli quand rien n'est forcé`() {
        assertEquals(sensor, chosenLens(0f, sensor))
    }

    @Test
    fun `sans capteur ni réglage le repli grand-angle s'applique`() {
        assertEquals(LensSpec.Default, chosenLens(0f, null))
    }

    @Test
    fun `côté lunettes le calibrage lunettes prime sur le capteur téléphone`() {
        val glasses = LensSpec(60f, 90f, sensorLandscape = false)
        assertEquals(glasses, chosenLens(0f, sensor, glasses, fromGlasses = true))
    }

    @Test
    fun `côté lunettes sans calibrage le repli s'applique, pas le capteur`() {
        assertEquals(LensSpec.Glasses, chosenLens(0f, sensor, glasses = null, fromGlasses = true))
    }

    @Test
    fun `côté lunettes le forcé utilise l'aspect 9 16 du flux`() {
        val forced = chosenLens(70f, sensor, fromGlasses = true)
        assertEquals(70f, forced.horizontalFovDeg, 0.001f)
        assertEquals(102.45f, forced.verticalFovDeg, 0.2f)
        assertFalse(forced.sensorLandscape)
    }

    @Test
    fun `côté lunettes le forcé prime sur la mire`() {
        val mire = LensSpec(60f, 90f, sensorLandscape = false)
        assertEquals(70f, chosenLens(70f, sensor, mire, fromGlasses = true).horizontalFovDeg, 0.001f)
    }

    @Test
    fun `côté lunettes le forcé extrême retombe sur la mire puis le repli`() {
        // 130° forcés : le vertical 9:16 sort à 150° des bornes caméra, inutilisable.
        val mire = LensSpec(60f, 90f, sensorLandscape = false)
        assertEquals(mire, chosenLens(130f, sensor, mire, fromGlasses = true))
        assertEquals(LensSpec.Glasses, chosenLens(130f, sensor, glasses = null, fromGlasses = true))
    }

    @Test
    fun `côté lunettes la mire prime sur le repli`() {
        val mire = LensSpec(60.61f, 92.19f, sensorLandscape = false)
        assertEquals(mire, chosenLens(0f, sensor, mire, fromGlasses = true))
    }

    @Test
    fun `côté lunettes la provenance suit l'étage`() {
        val mire = LensSpec(60f, 90f, sensorLandscape = false)
        assertEquals(LensOrigin.FORCED, chooseLens(70f, sensor, mire, fromGlasses = true).origin)
        assertEquals(LensOrigin.SENSOR, chooseLens(0f, sensor, mire, fromGlasses = true).origin)
        assertEquals(LensOrigin.FALLBACK, chooseLens(0f, sensor, glasses = null, fromGlasses = true).origin)
        assertTrue(chooseLens(0f, sensor, glasses = null, fromGlasses = true).isFallback)
    }

    @Test
    fun `côté téléphone le calibrage lunettes est ignoré`() {
        val glasses = LensSpec(60f, 90f, sensorLandscape = false)
        assertEquals(sensor, chosenLens(0f, sensor, glasses, fromGlasses = false))
    }

    @Test
    fun `forcé hors bornes retombe sur le capteur puis le repli`() {
        assertEquals(sensor, chosenLens(200f, sensor))
        assertEquals(sensor, chosenLens(Float.NaN, sensor))
        assertEquals(LensSpec.Default, chosenLens(200f, null))
    }

    @Test
    fun `capteur inutilisable retombe sur le repli`() {
        assertEquals(LensSpec.Default, chosenLens(0f, LensSpec(0f, 0f)))
    }

    @Test
    fun `forcé garde l'aspect du capteur au lieu du 4 3 supposé`() {
        val wide = LensSpec(70f, 40f)
        assertEquals(33.4f, chosenLens(60f, wide).verticalFovDeg, 0.2f)
    }

    @Test
    fun `chooseLens expose la provenance du calibrage`() {
        assertEquals(LensOrigin.FORCED, chooseLens(70f, sensor).origin)
        assertEquals(LensOrigin.SENSOR, chooseLens(0f, sensor).origin)
        assertEquals(LensOrigin.FALLBACK, chooseLens(0f, null).origin)
        assertTrue(chooseLens(0f, null).isFallback)
        assertFalse(chooseLens(0f, sensor).isFallback)
    }
}
