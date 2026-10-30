package dev.sam3vision.app.glasses

import com.meta.wearable.dat.camera.types.AudioCodec
import com.meta.wearable.dat.camera.types.AudioSampleRate
import com.meta.wearable.dat.camera.types.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configuration du flux des lunettes pour l'écran iSee ([glassesStreamConfiguration]),
 * prouvée sur JVM : le SDK s'instancie hors Android.
 *
 * Ce que ces tests verrouillent : la rue iSee est nourrie en **haute qualité**
 * (720×1280, ~1,5× d'upscale au lieu de ~2,1× en moyenne), à **15 i/s** (le
 * bitrate va au piqué spatial, pas au débit), en **H.265** (rendu direct, sans
 * ré-échantillonnage intermédiaire). Baisser la qualité ici, c'est reflouter
 * la rue : tout changement doit être volontaire et commenté.
 */
class GlassesStreamConfigTest {

    @Test
    fun `le direct iSee demande la haute qualite`() {
        assertEquals(VideoQuality.HIGH, glassesStreamConfiguration(null).videoQuality)
    }

    @Test
    fun `le direct iSee tourne a 15 images par seconde`() {
        assertEquals(GLASSES_FRAME_RATE, glassesStreamConfiguration(null).frameRate)
        assertEquals(15, GLASSES_FRAME_RATE)
    }

    @Test
    fun `le direct iSee recoit du H265`() {
        assertTrue(glassesStreamConfiguration(null).compressVideo)
    }

    @Test
    fun `sans micro la camera est demandee seule`() {
        assertNull(glassesStreamConfiguration(null).audioCodec)
    }

    @Test
    fun `le codec audio negocie est transmis tel quel`() {
        val codec = AudioCodec.PCM(AudioSampleRate.RATE_16000, 1)

        assertEquals(codec, glassesStreamConfiguration(codec).audioCodec)
    }
}
