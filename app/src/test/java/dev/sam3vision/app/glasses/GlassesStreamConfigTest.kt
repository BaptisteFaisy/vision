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
 * Ce que ces tests verrouillent : la rue iSee est nourrie en **qualité moyenne**
 * (504×896 — SAM reçoit du 320/q55 dans tous les cas, suréchantillonné 1,6×,
 * avec plus de bits par pixel qu'en haute à 15 i/s), à **24 i/s** (sur la
 * grille du SDK, repli 15 annoncé), en **H.265** (rendu direct, sans
 * ré-échantillonnage intermédiaire). Remonter la qualité ici, c'est reflouter
 * par manque de bits : tout changement doit être volontaire et commenté.
 */
class GlassesStreamConfigTest {

    @Test
    fun `le direct iSee demande la qualite moyenne`() {
        assertEquals(VideoQuality.MEDIUM, glassesStreamConfiguration(null).videoQuality)
    }

    @Test
    fun `un lien sature replie en qualite basse`() {
        assertEquals(0.05, GLASSES_LOW_DROP_RATE, 0.0)
        assertEquals(VideoQuality.MEDIUM, glassesVideoQuality(0.0))
        assertEquals(VideoQuality.MEDIUM, glassesVideoQuality(0.05))
        assertEquals(VideoQuality.LOW, glassesVideoQuality(0.051))
        assertEquals(VideoQuality.LOW, glassesStreamConfiguration(null, dropRate = 0.2).videoQuality)
        assertEquals(VideoQuality.MEDIUM, glassesStreamConfiguration(null, dropRate = 0.0).videoQuality)
    }

    @Test
    fun `le direct iSee demande 24 images par seconde`() {
        assertEquals(GLASSES_FRAME_RATE, glassesStreamConfiguration(null).frameRate)
        assertEquals(24, GLASSES_FRAME_RATE)
    }

    @Test
    fun `l echelle tente 24 puis replie sur 15 prouve`() {
        assertEquals(listOf(24, 15), GLASSES_FRAME_RATE_LADDER)
        assertEquals(GLASSES_FRAME_RATE, GLASSES_FRAME_RATE_LADDER.first())
        assertEquals(15, GLASSES_FRAME_RATE_LADDER.last())
    }

    @Test
    fun `un essai de repli porte sa propre cadence`() {
        assertEquals(15, glassesStreamConfiguration(null, frameRate = 15).frameRate)
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
