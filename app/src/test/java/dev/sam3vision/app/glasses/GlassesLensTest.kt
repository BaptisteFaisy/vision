package dev.sam3vision.app.glasses

import com.meta.wearable.dat.core.types.DeviceType
import dev.sam3vision.core.distance.LensSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Profils optiques lunettes : modèle → champ capteur, ramené au flux 9:16. */
class GlassesLensTest {

    @Test
    fun `ray-ban meta suit la mesure labo 66 degres`() {
        assertEquals(66f, glassesSensorH(DeviceType.RAYBAN_META), 0f)
    }

    @Test
    fun `oakley hstn suit la mesure labo 70 degres`() {
        assertEquals(70f, glassesSensorH(DeviceType.OAKLEY_META_HSTN), 0f)
    }

    @Test
    fun `vanguard derive du marketing 122 degres`() {
        assertEquals(95f, glassesSensorH(DeviceType.OAKLEY_META_VANGUARD), 0f)
    }

    @Test
    fun `modele inconnu ou absent retombe sur 68 degres`() {
        listOf(null, DeviceType.UNKNOWN, DeviceType.META_GLASSES, DeviceType.META_RAYBAN_DISPLAY).forEach {
            assertEquals(68f, glassesSensorH(it), 0f)
        }
    }

    @Test
    fun `optics partage l'optique presumee des ray-ban meta`() {
        assertEquals(glassesStreamLensFor(DeviceType.RAYBAN_META), glassesStreamLensFor(DeviceType.RAYBAN_META_OPTICS))
    }

    @Test
    fun `le flux resserre l'horizontal a focale constante`() {
        val stream = glassesStreamLensFor(DeviceType.RAYBAN_META)
        assertFalse(stream.sensorLandscape)
        assertEquals(51.94f, stream.horizontalFovDeg, 0.05f)
        assertEquals(81.78f, stream.verticalFovDeg, 0.05f)
        assertTrue(stream.usable)
    }

    @Test
    fun `le champ vertical derive colle aux mesures labo a 2 degres`() {
        assertEquals(83f, glassesStreamLensFor(DeviceType.RAYBAN_META).verticalFovDeg, 2f)
        assertEquals(87f, glassesStreamLensFor(DeviceType.OAKLEY_META_HSTN).verticalFovDeg, 2f)
    }

    @Test
    fun `la focale suit le fov angulaire aux dimensions du flux`() {
        // DAT HIGH 720×1280 : focale = 360 / tan(26,83°).
        assertEquals(711.6f, glassesStreamLensFor(null).focalPx(720, 1280), 0.5f)
    }

    @Test
    fun `sans sdk ni modele reconnu le profil connecte est nul`() {
        // JVM sans SDK : `connectedDeviceType()` rend null, l'appelant garde
        // son étage suivant (mire ou repli estimé).
        assertNull(connectedGlassesStreamLens())
    }

    @Test
    fun `sans modele le flux vaut le repli estime`() {
        assertEquals(LensSpec.Glasses, glassesStreamLensFor(null))
        assertEquals(LensSpec.Glasses, glassesStreamLensFor(DeviceType.UNKNOWN))
    }
}
